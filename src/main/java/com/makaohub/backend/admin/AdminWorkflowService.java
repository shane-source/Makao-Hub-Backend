package com.makaohub.backend.admin;

import com.makaohub.backend.messaging.service.ChatContactMaskingService;
import com.makaohub.backend.storage.service.SupabaseStorageClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import static com.makaohub.backend.admin.StaffRole.Permission.*;

@Service
@Transactional
public class AdminWorkflowService {
    private final AdminStore db;
    private final StaffAccessService access;
    private final SupabaseStorageClient storage;
    private final ChatContactMaskingService masking;
    public AdminWorkflowService(AdminStore db,StaffAccessService access,SupabaseStorageClient storage,ChatContactMaskingService masking) {
        this.db=db; this.access=access; this.storage=storage; this.masking=masking;
    }

    public AdminStore.Page verificationQueue(Jwt jwt,String role,String state,String search,int page,int size) {
        var staff=access.require(jwt);
        if (staff.role()!=StaffRole.STAFF_LEAD && staff.role()!=StaffRole.VERIFICATION_REVIEWER) throw forbidden(staff);
        if (role!=null) evidence(role);
        if (!Set.of("PENDING","VERIFIED","REJECTED").contains(state)) throw AdminStore.bad("Unknown verification state.");
        if (search!=null && search.length()>150) throw AdminStore.bad("Search is too long.");
        UUID reviewer=staff.role()==StaffRole.VERIFICATION_REVIEWER?staff.userId():null;
        var result=db.page("""
                SELECT v.user_id,v.role,u.full_name,v.status,v.submitted_at,v.updated_at,a.reviewer_id
                FROM role_verifications v JOIN users u ON u.id=v.user_id
                LEFT JOIN verification_assignments a ON a.user_id=v.user_id AND a.role=v.role
                WHERE v.role IN ('RENTER','LANDLORD','SUBLESSOR') AND v.status=?
                AND (?::text IS NULL OR v.role=?) AND (?::uuid IS NULL OR a.reviewer_id=?)
                AND (?::text IS NULL OR lower(u.full_name) LIKE lower(?) ESCAPE '\\')
                ORDER BY v.submitted_at,v.user_id,v.role
                """,page,size,state,role,role,reviewer,reviewer,search,search==null?null:"%"+search.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%");
        db.audit(staff.userId(),"VERIFICATION_QUEUE_VIEWED",null,"Assigned workload",null,"VIEWED");
        return result;
    }

    public Map<String,Object> assign(Jwt jwt,UUID command,UUID subject,String role,AdminRequests.Assign request) {
        var staff=access.require(jwt,ASSIGN_VERIFICATION); evidence(role);
        db.one("SELECT id FROM users WHERE id=? FOR UPDATE",subject);
        return db.command(staff.userId(),command,"ASSIGN:"+subject+":"+role+":"+request,() -> {
            db.one("SELECT user_id FROM role_verifications WHERE user_id=? AND role=? AND status='PENDING' FOR UPDATE",subject,role);
            if (!db.exists("SELECT EXISTS(SELECT 1 FROM staff_accounts WHERE user_id=? AND enabled AND staff_role='VERIFICATION_REVIEWER' AND can_view_documents)",request.reviewerId()))
                throw AdminStore.bad("Choose an active verification reviewer with document permission.");
            db.update("INSERT INTO verification_assignments(user_id,role,reviewer_id) VALUES (?,?,?) ON CONFLICT(user_id,role) DO UPDATE SET reviewer_id=EXCLUDED.reviewer_id,assigned_at=CURRENT_TIMESTAMP",
                    subject,role,request.reviewerId());
            db.audit(staff.userId(),"VERIFICATION_REVIEWER_ASSIGNED",subject,reason(request.reason()),null,"ASSIGNED");
            return Map.of("userId",subject,"role",role,"reviewerId",request.reviewerId());
        });
    }

    public Map<String,Object> verificationCase(Jwt jwt,UUID subject,String role) {
        var staff=assignedReviewer(jwt,subject,role);
        var source=evidence(role);
        var result=db.one("""
                SELECT v.user_id,v.role,v.status,v.submitted_at,v.updated_at,v.rejection_reason,
                       u.full_name,a.reviewer_id,reviewer.full_name AS reviewer_name
                FROM role_verifications v
                JOIN users u ON u.id=v.user_id
                LEFT JOIN verification_assignments a ON a.user_id=v.user_id AND a.role=v.role
                LEFT JOIN users reviewer ON reviewer.id=a.reviewer_id
                WHERE v.user_id=? AND v.role=?
                """,subject,role);
        result.put("documents",db.rows("SELECT d.id,d.document_type,d.content_type,d.size_bytes,d.status,d.updated_at,r.viewed_at,r.reviewed_at FROM "+source.table()+" d LEFT JOIN verification_document_reviews r ON r.document_id=d.id AND r.role=? AND r.reviewer_id=? AND r.evidence_updated_at=d.updated_at WHERE d."+source.owner()+"=? AND d.status='READY' ORDER BY d.document_type",role,staff.userId(),subject));
        db.audit(staff.userId(),"VERIFICATION_CASE_VIEWED",subject,"Assigned role review",null,"VIEWED");
        return result;
    }

    public record Document(byte[] bytes,String contentType) { }
    public Document document(Jwt jwt,UUID subject,String role,UUID document) {
        var staff=assignedReviewer(jwt,subject,role);
        var source=evidence(role);
        // Owner lock serializes evidence replacement, resubmission and review.
        db.one("SELECT id FROM users WHERE id=? FOR UPDATE",subject);
        db.one("SELECT user_id FROM role_verifications WHERE user_id=? AND role=? AND status='PENDING'",subject,role);
        var row=db.one("SELECT * FROM "+source.table()+" WHERE id=? AND "+source.owner()+"=? AND status='READY'",document,subject);
        String path=(String)row.get("object_path");
        if (!path.startsWith(subject+source.prefix())) throw AdminStore.conflict("Older evidence must be replaced through the protected upload flow.");
        long size=((Number)row.get("size_bytes")).longValue();
        byte[] bytes=storage.readVerificationDocument(path,size);
        db.update("""
                INSERT INTO verification_document_reviews(document_id,role,reviewer_id,evidence_updated_at)
                VALUES (?,?,?,?) ON CONFLICT(document_id,role,reviewer_id) DO UPDATE SET
                evidence_updated_at=EXCLUDED.evidence_updated_at,viewed_at=CURRENT_TIMESTAMP,
                reviewed_at=CASE WHEN verification_document_reviews.evidence_updated_at=EXCLUDED.evidence_updated_at
                    THEN verification_document_reviews.reviewed_at ELSE NULL END
                """,document,role,staff.userId(),Timestamp.from((Instant)row.get("updated_at")));
        db.audit(staff.userId(),"PRIVATE_DOCUMENT_VIEWED",document,"Assigned role review",null,"VIEW_AUTHORIZED");
        return new Document(bytes,(String)row.get("content_type"));
    }

    public Map<String,Object> markDocumentReviewed(Jwt jwt,UUID command,UUID subject,String role,UUID document) {
        var staff=assignedReviewer(jwt,subject,role);
        var source=evidence(role);
        return db.command(staff.userId(),command,"DOCUMENT_REVIEW:"+subject+":"+role+":"+document,() -> {
            db.one("SELECT id FROM users WHERE id=? FOR UPDATE",subject);
            int count=db.update("UPDATE verification_document_reviews r SET reviewed_at=CURRENT_TIMESTAMP WHERE document_id=? AND role=? AND reviewer_id=? AND EXISTS(SELECT 1 FROM "+source.table()+" d WHERE d.id=r.document_id AND d."+source.owner()+"=? AND d.status='READY' AND d.updated_at=r.evidence_updated_at)",document,role,staff.userId(),subject);
            if (count!=1) throw AdminStore.conflict("View the current document before marking it reviewed.");
            db.audit(staff.userId(),"PRIVATE_DOCUMENT_REVIEWED",document,"Reviewer acknowledged current evidence", "VIEWED","REVIEWED");
            return Map.of("documentId",document,"state","REVIEWED");
        });
    }

    public Map<String,Object> decideVerification(Jwt jwt,UUID command,UUID subject,String role,AdminRequests.Decision request) {
        var staff=assignedReviewer(jwt,subject,role);
        var source=evidence(role);
        return db.command(staff.userId(),command,"VERIFICATION:"+subject+":"+role+":"+request,() -> {
            db.one("SELECT id FROM users WHERE id=? AND account_status='ACTIVE' FOR UPDATE",subject);
            var previous=db.one("SELECT status,updated_at FROM role_verifications WHERE user_id=? AND role=? FOR UPDATE",subject,role);
            checkVersion(previous,request.expectedUpdatedAt());
            if (!"PENDING".equals(previous.get("status"))) throw AdminStore.conflict("Only pending verification can be decided.");
            if (request.approve()) {
                var documents=db.rows("SELECT d.id,d.document_type,d.object_path,r.reviewed_at FROM "+source.table()+" d LEFT JOIN verification_document_reviews r ON r.document_id=d.id AND r.role=? AND r.reviewer_id=? AND r.evidence_updated_at=d.updated_at WHERE d."+source.owner()+"=? AND d.status='READY'",role,staff.userId(),subject);
                var reviewed=new HashSet<String>();
                for (var d:documents) if (d.get("reviewed_at")!=null && ((String)d.get("object_path")).startsWith(subject+source.prefix())) reviewed.add((String)d.get("document_type"));
                var required=new HashSet<>(Set.of("ID_FRONT","ID_BACK","SELFIE"));
                if (role.equals("LANDLORD")) required.add("OWNERSHIP_PROOF");
                if (role.equals("SUBLESSOR")) required.add("RENTAL_AGREEMENT");
                if (!reviewed.containsAll(required)) throw AdminStore.conflict("View and mark every required current document reviewed before approval.");
            }
            String next=request.approve()?"VERIFIED":"REJECTED", safeReason=reason(request.reason());
            db.update("UPDATE role_verifications SET status=?,reviewed_at=CURRENT_TIMESTAMP,reviewed_by=?,rejection_reason=?,updated_at=CURRENT_TIMESTAMP WHERE user_id=? AND role=?",
                    next,staff.userId(),request.approve()?null:safeReason,subject,role);
            notifyUser(subject,"VERIFICATION_DECISION","Verification update",request.approve()?"Your role verification was approved.":"Your verification needs correction. Open your verification screen for the review reason.");
            db.audit(staff.userId(),"ROLE_VERIFICATION_DECIDED",subject,safeReason,"PENDING",next);
            return Map.of("userId",subject,"role",role,"state",next);
        });
    }

    public AdminStore.Page listings(Jwt jwt,String kind,String state,int page,int size) {
        var staff=access.require(jwt,SAFETY_VIEW); listing(kind);
        if (!Set.of("PENDING","APPROVED","REJECTED").contains(state)) throw AdminStore.bad("Unknown moderation state.");
        var source=listing(kind);
        var result=db.page("SELECT id,title,status,moderation_status,moderation_reason,moderation_submitted_at,updated_at FROM "+source.table()+" WHERE moderation_status=? AND moderation_submitted_at IS NOT NULL AND status<>'ARCHIVED' ORDER BY moderation_submitted_at,id",page,size,state);
        db.audit(staff.userId(),"LISTING_QUEUE_VIEWED",null,"Listing moderation",null,"VIEWED"); return result;
    }

    public Map<String,Object> listingDetail(Jwt jwt,String kind,UUID id) {
        var staff=access.require(jwt,SAFETY_VIEW); var source=listing(kind);
        String typeColumn=kind.equals("LANDLORD")?"property_type":"space_type";
        String bedroomExpression=kind.equals("LANDLORD")?"l.bedroom_count::text":"'1 private room'";
        var result=db.one("SELECT l.id,l.title,l.description,l.monthly_rent_kes,l.status,l.moderation_status,"+
                "l.moderation_reason,l.updated_at,l."+typeColumn+" AS listing_type,"+bedroomExpression+" AS bedrooms,"+
                "u.full_name AS owner_name,EXISTS(SELECT 1 FROM role_verifications v WHERE v.user_id=u.id AND v.role=? AND v.status='VERIFIED') AS owner_verified,"+
                "COALESCE(loc.area_label,'Area not provided') AS public_area FROM "+source.table()+" l JOIN users u ON u.id=l."+source.owner()+
                " LEFT JOIN "+source.location()+" loc ON loc."+source.foreignKey()+"=l.id WHERE l.id=?",source.role(),id);
        result.put("locationSaved",!"Area not provided".equals(result.get("public_area")));
        result.put("media",db.rows("SELECT id,media_type,content_type,sort_order FROM "+source.media()+
                " WHERE "+source.foreignKey()+"=? AND confirmed_at IS NOT NULL ORDER BY sort_order",id));
        db.audit(staff.userId(),"LISTING_REVIEW_VIEWED",id,"Media and claim review; exact location omitted",null,"VIEWED"); return result;
    }

    public Document listingMedia(Jwt jwt,String kind,UUID id,UUID media) {
        var staff=access.require(jwt,SAFETY_VIEW); var source=listing(kind);
        var row=db.one("SELECT object_path,content_type,size_bytes FROM "+source.media()+
                " WHERE id=? AND "+source.foreignKey()+"=? AND confirmed_at IS NOT NULL",media,id);
        byte[] bytes=storage.readListingMedia((String)row.get("object_path"),((Number)row.get("size_bytes")).longValue());
        db.audit(staff.userId(),"LISTING_MEDIA_VIEWED",media,"Listing moderation media",null,"VIEW_AUTHORIZED");
        return new Document(bytes,(String)row.get("content_type"));
    }

    public Map<String,Object> decideListing(Jwt jwt,UUID command,String kind,UUID id,AdminRequests.Decision request) {
        var staff=access.require(jwt,LISTING_MODERATION); var source=listing(kind);
        return db.command(staff.userId(),command,"LISTING:"+kind+":"+id+":"+request,() -> {
            var row=db.one("SELECT * FROM "+source.table()+" WHERE id=? FOR UPDATE",id); checkVersion(row,request.expectedUpdatedAt());
            if (!Set.of("DRAFT","PAUSED").contains(row.get("status"))) throw AdminStore.conflict("This listing is not available for a moderation decision.");
            if (!"PENDING".equals(row.get("moderation_status")) || row.get("moderation_submitted_at")==null) throw AdminStore.conflict("Listing is not submitted for review.");
            UUID owner=(UUID)row.get(source.owner());
            if (request.approve()) requireCompleteListing(source,id,owner);
            String next=request.approve()?"APPROVED":"REJECTED";
            boolean restore=request.approve() && Boolean.TRUE.equals(row.get("restore_publication_after_review"));
            String status=!request.approve()?"DRAFT":(restore?"PUBLISHED":(String)row.get("status"));
            db.update("UPDATE "+source.table()+" SET moderation_status=?,moderation_reason=?,moderation_reviewed_by=?,moderation_reviewed_at=CURRENT_TIMESTAMP,status=?,updated_at=CURRENT_TIMESTAMP,restore_publication_after_review=FALSE WHERE id=?",
                    next,reason(request.reason()),staff.userId(),status,id);
            notifyUser(owner,"LISTING_REVIEWED","Listing review update",request.approve()?"Your listing was approved. Open your listings to check publication.":"Your listing needs changes. Open the listing for the review reason.");
            db.audit(staff.userId(),"LISTING_MODERATION_DECIDED",id,reason(request.reason()),"PENDING",next);
            return Map.of("listingId",id,"moderationStatus",next,"status",status);
        });
    }

    public Map<String,Object> submitListing(Jwt jwt,String kind,UUID id) {
        var source=listing(kind);
        if (!List.of(source.role()).equals(jwt.getClaimAsStringList("roles"))) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        UUID owner=UUID.fromString(jwt.getSubject());
        var row=db.one("SELECT status FROM "+source.table()+" WHERE id=? AND "+source.owner()+"=? FOR UPDATE",id,owner);
        if (!Set.of("DRAFT","PAUSED").contains(row.get("status"))) throw AdminStore.conflict("Only draft or paused listings can be submitted.");
        requireCompleteListing(source,id,owner);
        db.update("UPDATE "+source.table()+" SET moderation_status='PENDING',moderation_reason=NULL,moderation_submitted_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP,restore_publication_after_review=FALSE WHERE id=?",id);
        db.audit(owner,"LISTING_SUBMITTED_FOR_REVIEW",id,"Owner submitted current listing",(String)row.get("status"),"PENDING");
        return Map.of("listingId",id,"moderationStatus","PENDING");
    }

    public AdminStore.Page flags(Jwt jwt,int page,int size) {
        var staff=access.require(jwt,SAFETY_VIEW);
        var result=db.page("""
                SELECT m.*,r.state AS review_state FROM (
                  SELECT id,chat_thread_id,sender_id,masked_body,moderation_reason,created_at,'RENTAL' AS kind FROM rental_chat_messages WHERE moderation_status='FLAGGED'
                  UNION ALL
                  SELECT id,chat_thread_id,sender_id,masked_body,moderation_reason,created_at,'ROOMMATE' AS kind FROM roommate_chat_messages WHERE moderation_status='FLAGGED'
                ) m LEFT JOIN admin_message_reviews r ON r.message_id=m.id AND r.conversation_kind=m.kind
                ORDER BY m.created_at DESC,m.kind,m.id
                """,page,size);
        result.items().forEach(row -> row.put("masked_body",masking.mask((String)row.get("masked_body")).content()));
        db.audit(staff.userId(),"FLAGGED_MESSAGES_VIEWED",null,"Masked contact-sharing review",null,"VIEWED"); return result;
    }

    public Map<String,Object> flagAction(Jwt jwt,UUID command,String kind,UUID message,AdminRequests.MessageAction request) {
        var staff=access.require(jwt,SAFETY); String table=messageTable(kind);
        return db.command(staff.userId(),command,"FLAG:"+kind+":"+message+":"+request,() -> {
            var row=db.one("SELECT sender_id FROM "+table+" WHERE id=? AND moderation_status='FLAGGED' FOR UPDATE",message);
            String next=request.escalate()?"ESCALATED":"WARNED";
            db.update("INSERT INTO admin_message_reviews(message_id,conversation_kind,state,reviewed_by,reason) VALUES (?,?,?,?,?) ON CONFLICT(message_id,conversation_kind) DO UPDATE SET state=EXCLUDED.state,reviewed_by=EXCLUDED.reviewed_by,reason=EXCLUDED.reason,reviewed_at=CURRENT_TIMESTAMP",message,kind,next,staff.userId(),reason(request.reason()));
            if (!request.escalate()) notifyUser((UUID)row.get("sender_id"),"MODERATION_WARNING","Chat safety warning","Keep communication inside Makao Hub. Contact-sharing attempts remain masked and flagged.");
            db.audit(staff.userId(),"MESSAGE_FLAG_REVIEWED",message,reason(request.reason()),"FLAGGED",next);
            return Map.of("messageId",message,"state",next);
        });
    }

    public AdminStore.Page payments(Jwt jwt,int page,int size) {
        var staff=access.require(jwt,PAYMENTS_VIEW);
        var result=db.page("SELECT id,payment_type,booking_id,amount_minor,currency,status,created_at,completed_at FROM payment_intents ORDER BY created_at DESC,id",page,size);
        db.audit(staff.userId(),"PAYMENT_RECORDS_VIEWED",null,"Permissioned read-only payment review",null,"VIEWED"); return result;
    }

    public AdminStore.Page bookings(Jwt jwt,int page,int size) {
        var staff=access.require(jwt,PAYMENTS_VIEW);
        var result=db.page("SELECT id,listing_id,listing_kind,rent_amount_minor,deposit_amount_minor,commission_amount_minor,total_amount_minor,currency,status,move_in_confirmed_at,release_eligible_at,released_at FROM bookings ORDER BY created_at DESC,id",page,size);
        db.audit(staff.userId(),"BOOKING_RECORDS_VIEWED",null,"Read-only held-payment and release history",null,"VIEWED"); return result;
    }

    public Map<String,Object> reviseListing(Jwt jwt,String kind,UUID id) {
        var source=listing(kind);
        if (!List.of(source.role()).equals(jwt.getClaimAsStringList("roles"))) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        UUID owner=UUID.fromString(jwt.getSubject());
        var row=db.one("SELECT id,status FROM "+source.table()+" WHERE id=? AND "+source.owner()+"=? AND status IN ('DRAFT','PAUSED') AND moderation_status='REJECTED' FOR UPDATE",id,owner);
        db.update("UPDATE "+source.table()+" SET status='DRAFT',moderation_status='PENDING',moderation_submitted_at=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=?",id);
        db.audit(owner,"REJECTED_LISTING_REVISION_STARTED",id,"Owner opened a draft for correction",row.get("status")+"_REJECTED","DRAFT");
        return Map.of("listingId",id,"status","DRAFT");
    }

    public AdminStore.Page audit(Jwt jwt,UUID actor,UUID subject,int page,int size) {
        var staff=access.require(jwt,AUDIT);
        db.audit(staff.userId(),"AUDIT_HISTORY_VIEWED",subject,"Operational audit review",null,"VIEWED");
        return db.page("SELECT e.id,e.actor_id,u.full_name AS actor_name,e.action,e.subject_id,e.reason,e.previous_state,e.new_state,e.occurred_at FROM admin_audit_events e LEFT JOIN users u ON u.id=e.actor_id WHERE (?::uuid IS NULL OR e.actor_id=?) AND (?::uuid IS NULL OR e.subject_id=?) ORDER BY e.occurred_at DESC,e.id DESC",page,size,actor,actor,subject,subject);
    }

    public AdminStore.Page activity(Jwt jwt,int page,int size) {
        var staff=access.require(jwt);
        db.audit(staff.userId(),"OWN_ACTIVITY_VIEWED",null,"Staff reviewed their own activity",null,"VIEWED");
        return db.page("SELECT e.id,e.actor_id,u.full_name AS actor_name,e.action,e.subject_id,e.reason,e.previous_state,e.new_state,e.occurred_at FROM admin_audit_events e LEFT JOIN users u ON u.id=e.actor_id WHERE e.actor_id=? ORDER BY e.occurred_at DESC,e.id DESC",page,size,staff.userId());
    }

    public Map<String,Object> export(Jwt jwt,UUID command,AdminRequests.AuditExport request) {
        var staff=access.require(jwt,AUDIT);
        return db.command(staff.userId(),command,"AUDIT_EXPORT:"+request,() -> {
            db.audit(staff.userId(),"AUDIT_EXPORT_REQUESTED",request.subject(),reason(request.reason()),null,"EXPORTED_PAGE");
            var result=db.page("SELECT id,actor_id,action,subject_id,reason,previous_state,new_state,occurred_at FROM admin_audit_events WHERE (?::uuid IS NULL OR actor_id=?) AND (?::uuid IS NULL OR subject_id=?) ORDER BY occurred_at DESC,id DESC",request.page(),request.size(),request.actor(),request.actor(),request.subject(),request.subject());
            return Map.of("items",result.items(),"page",result.page(),"size",result.size(),"hasNext",result.hasNext());
        });
    }

    public Map<String,Object> ownListingReview(Jwt jwt,String kind,UUID id) {
        var source=listing(kind);
        if (!List.of(source.role()).equals(jwt.getClaimAsStringList("roles"))) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return db.one("SELECT id,status,moderation_status,moderation_reason,moderation_submitted_at,moderation_reviewed_at,updated_at FROM "+source.table()+" WHERE id=? AND "+source.owner()+"=?",id,UUID.fromString(jwt.getSubject()));
    }

    public AdminStore.Page staff(Jwt jwt,int page,int size) {
        var actor=access.require(jwt,STAFF);
        var result=db.page("SELECT a.user_id,u.full_name,a.staff_role,a.can_view_documents,a.enabled,a.created_at FROM staff_accounts a JOIN users u ON u.id=a.user_id ORDER BY a.created_at,a.user_id",page,size);
        db.audit(actor.userId(),"STAFF_DIRECTORY_VIEWED",null,"Staff permission management",null,"VIEWED"); return result;
    }

    private void requireCompleteListing(ListingSource source,UUID id,UUID owner) {
        if (!db.exists("SELECT EXISTS(SELECT 1 FROM role_verifications v JOIN users u ON u.id=v.user_id WHERE v.user_id=? AND v.role=? AND v.status='VERIFIED' AND u.account_status='ACTIVE')",owner,source.role())) throw AdminStore.conflict("The owner must have current role verification.");
        if (!db.exists("SELECT EXISTS(SELECT 1 FROM "+source.location()+" WHERE "+source.foreignKey()+"=?)",id)
                || !db.exists("SELECT EXISTS(SELECT 1 FROM "+source.media()+" WHERE "+source.foreignKey()+"=? AND media_type='IMAGE' AND confirmed_at IS NOT NULL)",id))
            throw AdminStore.conflict("A private location and confirmed photo are required.");
    }

    private StaffAccessService.Staff assignedReviewer(Jwt jwt,UUID subject,String role) {
        var staff=access.require(jwt,VERIFICATION);
        evidence(role);
        // Owner first, then assignment, then evidence: same order as uploads and reassignment.
        db.one("SELECT id FROM users WHERE id=? FOR UPDATE",subject);
        access.requireAssignment(staff,subject,role);
        return staff;
    }

    void notifyUser(UUID user,String type,String title,String body) {
        db.update("INSERT INTO notifications(user_id,type,title,body) SELECT id,?,?,? FROM users WHERE id=? AND account_status='ACTIVE'",type,title,body,user);
    }
    static String reason(String value) {
        if (value==null || value.isBlank() || value.length()>500 || value.matches("(?s).*[@\\r\\n].*") || value.matches("(?s).*\\d{5,}.*"))
            throw AdminStore.bad("Use a short reason without contact details, ID numbers or document contents.");
        return value.strip();
    }
    private static void checkVersion(Map<String,Object> row,Instant expected) {
        if (!Objects.equals(row.get("updated_at"),expected)) throw AdminStore.conflict("The record changed. Reload before deciding.");
    }
    private ResponseStatusException forbidden(StaffAccessService.Staff staff) {
        db.denied(staff.userId()); return new ResponseStatusException(HttpStatus.FORBIDDEN,"This staff permission is not assigned.");
    }
    private record EvidenceSource(String table,String owner,String prefix) { }
    private static EvidenceSource evidence(String role) {
        return switch (role) {
            case "RENTER" -> new EvidenceSource("renter_verification_documents","renter_id","/renter-evidence-v1/");
            case "LANDLORD" -> new EvidenceSource("landlord_verification_documents","landlord_id","/evidence-v1/");
            case "SUBLESSOR" -> new EvidenceSource("sublessor_verification_documents","sublessor_id","/sublessor-evidence-v1/");
            default -> throw AdminStore.bad("Unknown verification role.");
        };
    }
    record ListingSource(String table,String owner,String role,String location,String media,String foreignKey) { }
    static ListingSource listing(String kind) {
        return switch (kind) {
            case "LANDLORD" -> new ListingSource("listings","landlord_id","LANDLORD","listing_locations","listing_media","listing_id");
            case "SUBLESSOR" -> new ListingSource("sublessor_listings","sublessor_id","SUBLESSOR","sublessor_listing_locations","sublessor_listing_media","sublessor_listing_id");
            default -> throw AdminStore.bad("Unknown listing kind.");
        };
    }
    static String messageTable(String kind) {
        return switch (kind) { case "RENTAL" -> "rental_chat_messages"; case "ROOMMATE" -> "roommate_chat_messages"; default -> throw AdminStore.bad("Unknown conversation kind."); };
    }
}

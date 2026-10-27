package com.makaohub.backend.admin;

import com.makaohub.backend.messaging.service.ChatContactMaskingService;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@Service
@Transactional
public class UserReportService {
    private final AdminStore db;
    private final StaffAccessService access;
    private final ChatContactMaskingService masking;
    private final AdminWorkflowService workflow;
    public UserReportService(AdminStore db,StaffAccessService access,ChatContactMaskingService masking,AdminWorkflowService workflow) {
        this.db=db; this.access=access; this.masking=masking; this.workflow=workflow;
    }

    public Map<String,Object> create(Jwt jwt,AdminRequests.Report request) {
        if (jwt==null || jwt.getClaimAsStringList("roles")==null || jwt.getClaimAsStringList("roles").size()!=1
                || !Set.of("RENTER","LANDLORD","SUBLESSOR").contains(jwt.getClaimAsStringList("roles").getFirst()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        UUID reporter=UUID.fromString(jwt.getSubject());
        db.one("SELECT id FROM users WHERE id=? AND account_status='ACTIVE' FOR UPDATE",reporter);
        if (!Set.of("MISLEADING_CONTENT","PAYMENT_REQUEST","UNSAFE_CONTENT","OTHER").contains(request.category())) throw AdminStore.bad("Unknown report category.");
        if (request.description().matches("(?s).*\\d{5,}.*")) throw AdminStore.bad("Do not include identity numbers or private documents in a report.");
        switch (request.subjectKind()) {
            case "LANDLORD_LISTING", "SHARED_LISTING" -> {
                var source=AdminWorkflowService.listing(request.subjectKind().equals("LANDLORD_LISTING")?"LANDLORD":"SUBLESSOR");
                db.one("SELECT id FROM "+source.table()+" WHERE id=? AND status='PUBLISHED' AND moderation_status='APPROVED'",request.subjectId());
            }
            case "RENTAL_MESSAGE", "ROOMMATE_MESSAGE" -> {
                boolean rental=request.subjectKind().equals("RENTAL_MESSAGE");
                String messages=rental?"rental_chat_messages":"roommate_chat_messages";
                String threads=rental?"rental_chat_threads":"roommate_chat_threads";
                String host=rental?"landlord_id":"sublessor_id";
                db.one("SELECT m.id FROM "+messages+" m JOIN "+threads+" t ON t.id=m.chat_thread_id WHERE m.id=? AND (t.renter_id=? OR t."+host+"=?)",request.subjectId(),reporter,reporter);
            }
            default -> throw AdminStore.bad("Unknown report subject.");
        }
        if (db.exists("SELECT (SELECT count(*) FROM user_reports WHERE reporter_id=? AND created_at>CURRENT_TIMESTAMP-INTERVAL '1 hour')>=10",reporter))
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"Too many reports. Please try later.");
        UUID id=UUID.randomUUID();
        db.update("INSERT INTO user_reports(id,reporter_id,subject_kind,subject_id,category,safe_description) VALUES (?,?,?,?,?,?)",
                id,reporter,request.subjectKind(),request.subjectId(),request.category(),masking.mask(request.description()).content());
        db.audit(reporter,"USER_REPORT_CREATED",id,"User submitted a safety concern",null,"OPEN");
        return Map.of("reportId",id,"state","OPEN");
    }

    public AdminStore.Page queue(Jwt jwt,String state,int page,int size) {
        var staff=access.require(jwt,StaffRole.Permission.SAFETY_VIEW);
        if (!Set.of("OPEN","INVESTIGATING","ESCALATED","RESOLVED").contains(state)) throw AdminStore.bad("Unknown report state.");
        var result=db.page("""
                SELECT r.id,r.subject_kind,r.subject_id,r.category,r.safe_description,r.priority,r.state,
                       r.assigned_to,r.resolution_reason,r.created_at,r.updated_at,
                       CASE r.subject_kind
                         WHEN 'LANDLORD_LISTING' THEN (SELECT title FROM listings WHERE id=r.subject_id)
                         WHEN 'SHARED_LISTING' THEN (SELECT title FROM sublessor_listings WHERE id=r.subject_id)
                         WHEN 'RENTAL_MESSAGE' THEN 'Rental conversation'
                         WHEN 'ROOMMATE_MESSAGE' THEN 'Roommate conversation'
                       END AS subject_label
                FROM user_reports r WHERE r.state=? ORDER BY r.created_at,r.id
                """,page,size,state);
        db.audit(staff.userId(),"REPORT_QUEUE_VIEWED",null,"Safety report triage",null,"VIEWED"); return result;
    }

    public Map<String,Object> act(Jwt jwt,UUID command,UUID id,AdminRequests.ReportAction request) {
        var staff=access.require(jwt,StaffRole.Permission.SAFETY);
        if (!Set.of("INVESTIGATING","ESCALATED","RESOLVED").contains(request.state()) || !Set.of("NORMAL","HIGH").contains(request.priority()))
            throw AdminStore.bad("Unknown report action or priority.");
        return db.command(staff.userId(),command,"REPORT:"+id+":"+request,() -> {
            var row=db.one("SELECT * FROM user_reports WHERE id=? FOR UPDATE",id);
            if (row.get("assigned_to")!=null && !staff.userId().equals(row.get("assigned_to"))) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"This report is assigned to another reviewer.");
            if ("RESOLVED".equals(row.get("state"))) throw AdminStore.conflict("A resolved report cannot be changed.");
            String safeReason=AdminWorkflowService.reason(request.reason());
            if (request.pauseListing()) {
                String kind=switch ((String)row.get("subject_kind")) { case "LANDLORD_LISTING" -> "LANDLORD"; case "SHARED_LISTING" -> "SUBLESSOR"; default -> throw AdminStore.bad("Only listing reports can pause a listing."); };
                var source=AdminWorkflowService.listing(kind);
                var listing=db.one("SELECT status FROM "+source.table()+" WHERE id=? FOR UPDATE",row.get("subject_id"));
                db.update("UPDATE "+source.table()+" SET status=CASE WHEN status='PUBLISHED' THEN 'PAUSED' ELSE status END,moderation_status='REJECTED',moderation_reason=?,moderation_reviewed_by=?,moderation_reviewed_at=CURRENT_TIMESTAMP,restore_publication_after_review=FALSE,updated_at=CURRENT_TIMESTAMP WHERE id=?",safeReason,staff.userId(),row.get("subject_id"));
                db.audit(staff.userId(),"REPORT_LISTING_PAUSED",(UUID)row.get("subject_id"),safeReason,(String)listing.get("status"),"REJECTED_NOT_PUBLIC");
            }
            db.update("UPDATE user_reports SET state=?,priority=?,assigned_to=?,resolution_reason=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",request.state(),request.priority(),staff.userId(),safeReason,id);
            if (request.notifyUsers()) {
                workflow.notifyUser((UUID)row.get("reporter_id"),"REPORT_RESOLVED","Safety report update","Your report was reviewed. Its current state is "+request.state()+".");
                var targets=switch ((String)row.get("subject_kind")) {
                    case "LANDLORD_LISTING" -> db.rows("SELECT landlord_id AS user_id FROM listings WHERE id=?",row.get("subject_id"));
                    case "SHARED_LISTING" -> db.rows("SELECT sublessor_id AS user_id FROM sublessor_listings WHERE id=?",row.get("subject_id"));
                    case "RENTAL_MESSAGE" -> db.rows("SELECT sender_id AS user_id FROM rental_chat_messages WHERE id=?",row.get("subject_id"));
                    case "ROOMMATE_MESSAGE" -> db.rows("SELECT sender_id AS user_id FROM roommate_chat_messages WHERE id=?",row.get("subject_id"));
                    default -> List.<Map<String,Object>>of();
                };
                for (var target:targets) if (!row.get("reporter_id").equals(target.get("user_id")))
                    workflow.notifyUser((UUID)target.get("user_id"),"REPORT_RESOLVED","Content review update","Your content was reviewed by the safety team. Open Makao Hub to check its current state.");
            }
            db.audit(staff.userId(),"REPORT_REVIEWED",id,safeReason,(String)row.get("state"),request.state());
            return Map.of("reportId",id,"state",request.state());
        });
    }
}

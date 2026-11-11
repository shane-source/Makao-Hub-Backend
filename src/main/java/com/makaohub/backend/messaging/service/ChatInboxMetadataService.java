package com.makaohub.backend.messaging.service;

import com.makaohub.backend.storage.service.SupabaseStorageClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** Fetches participant-scoped, already-masked inbox previews in one query per page. */
@Service
public class ChatInboxMetadataService {
    public enum Kind { RENTAL, ROOMMATE }
    public record Summary(String name, String avatarUrl, String lastMessage, Instant lastMessageAt) {
        @Override public String toString() { return "Summary[redacted]"; }
    }
    private final JdbcTemplate sql;
    private final SupabaseStorageClient storage;
    private final ChatContactMaskingService masking;
    public ChatInboxMetadataService(JdbcTemplate sql, SupabaseStorageClient storage, ChatContactMaskingService masking) {
        this.sql = sql; this.storage = storage; this.masking = masking;
    }

    public Map<UUID, Summary> forThreads(UUID actor, Kind kind, List<UUID> ids) {
        if (ids.isEmpty()) return Map.of();
        String threads = kind == Kind.RENTAL ? "rental_chat_threads" : "roommate_chat_threads";
        String messages = kind == Kind.RENTAL ? "rental_chat_messages" : "roommate_chat_messages";
        String owner = kind == Kind.RENTAL ? "landlord_id" : "sublessor_id";
        String ownerRole = kind == Kind.RENTAL ? "LANDLORD" : "SUBLESSOR";
        String open = kind == Kind.ROOMMATE ? " AND t.status='OPEN'" : "";
        String marks = String.join(",", Collections.nCopies(ids.size(), "?"));
        String query = "SELECT t.id,p.full_name,p.avatar_object_path,m.masked_body,m.created_at FROM " + threads + " t "
                + "JOIN users viewer ON viewer.id=? AND viewer.account_status='ACTIVE' "
                + "JOIN users p ON p.id=CASE WHEN t.renter_id=? THEN t." + owner + " ELSE t.renter_id END "
                + "LEFT JOIN LATERAL (SELECT masked_body,created_at FROM " + messages + " WHERE chat_thread_id=t.id ORDER BY created_at DESC,id DESC LIMIT 1) m ON TRUE "
                + "WHERE t.id IN (" + marks + ") AND (t.renter_id=? OR t." + owner + "=?)" + open
                + " AND EXISTS (SELECT 1 FROM user_roles ur JOIN roles r ON r.id=ur.role_id WHERE ur.user_id=? AND r.name=CASE WHEN t.renter_id=? THEN 'RENTER' ELSE '" + ownerRole + "' END)";
        List<Object> args = new ArrayList<>(); args.add(actor); args.add(actor); args.addAll(ids);
        args.add(actor); args.add(actor); args.add(actor); args.add(actor);
        Map<UUID, Summary> result = new HashMap<>();
        Map<String, Optional<String>> signedAvatars = new HashMap<>();
        sql.query(query, rs -> {
            String path = rs.getString("avatar_object_path"), url = null;
            if (path != null) url = signedAvatars.computeIfAbsent(path, value -> {
                try { return Optional.ofNullable(storage.createSignedAvatarReadUrl(value)); }
                catch (RuntimeException ignored) { return Optional.empty(); }
            }).orElse(null);
            String body = rs.getString("masked_body");
            Timestamp created = rs.getTimestamp("created_at");
            result.put(rs.getObject("id", UUID.class), new Summary(rs.getString("full_name"), url,
                    body == null ? null : masking.mask(body).content(), created == null ? null : created.toInstant()));
        }, args.toArray());
        return result;
    }
}

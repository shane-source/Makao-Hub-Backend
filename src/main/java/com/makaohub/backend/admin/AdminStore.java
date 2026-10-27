package com.makaohub.backend.admin;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.util.*;
import java.util.function.Supplier;

/** JDBC only for the new admin module. SQL identifiers at callers come from fixed allowlists. */
@Repository
public class AdminStore {
    private final JdbcTemplate sql;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    public AdminStore(JdbcTemplate sql) {
        this.sql = sql;
        var dates=new com.fasterxml.jackson.databind.module.SimpleModule();
        dates.addSerializer(java.time.Instant.class,new com.fasterxml.jackson.databind.JsonSerializer<>() {
            @Override public void serialize(java.time.Instant value,com.fasterxml.jackson.core.JsonGenerator output,
                    com.fasterxml.jackson.databind.SerializerProvider provider) throws java.io.IOException { output.writeString(value.toString()); }
        });
        json.registerModule(dates);
    }
    public int update(String statement, Object... arguments) { return sql.update(statement, arguments); }
    public void execute(String statement) { sql.execute(statement); }

    public List<Map<String,Object>> rows(String query, Object... args) {
        var rows = sql.queryForList(query, args);
        rows.forEach(row -> row.replaceAll((key,value) -> value instanceof Timestamp t ? t.toInstant() : value));
        return rows;
    }

    public Map<String,Object> one(String query, Object... args) {
        var rows = rows(query, args);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Record not found.");
        return rows.getFirst();
    }

    public boolean exists(String query, Object... args) {
        return Boolean.TRUE.equals(sql.queryForObject(query, Boolean.class, args));
    }

    public record Page(List<Map<String,Object>> items, int page, int size, boolean hasNext) { }
    public Page page(String query, int page, int size, Object... args) {
        if (page < 0 || page > 10_000 || size < 1 || size > 50) throw bad("Page size must be 1–50; page must be 0–10000.");
        var parameters = new ArrayList<>(Arrays.asList(args));
        parameters.add(size + 1); parameters.add((long) page * size);
        var found = rows(query + " LIMIT ? OFFSET ?", parameters.toArray());
        return new Page(found.stream().limit(size).toList(), page, size, found.size() > size);
    }

    public void audit(UUID actor, String action, UUID subject, String reason, String before, String after) {
        sql.update("INSERT INTO admin_audit_events(actor_id,action,subject_id,reason,previous_state,new_state) VALUES (?,?,?,?,?,?)",
                actor,action,subject,reason,before,after);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void denied(UUID actor) {
        audit(actor, "STAFF_ACCESS_DENIED", null, "Missing active MFA session, permission or case assignment", null, "DENIED");
    }

    /** Caller must hold a transaction. Advisory lock serializes even the first use of a key. */
    public Map<String,Object> command(UUID actor, UUID key, String request, Supplier<Map<String,Object>> operation) {
        if (key == null) throw bad("An Idempotency-Key UUID is required.");
        sql.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
            try (var lock = connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?,0))")) {
                lock.setString(1, actor + ":" + key); lock.execute();
            }
            return null;
        });
        var previous = rows("SELECT request_digest,response::text AS response FROM admin_commands WHERE actor_id=? AND command_key=?", actor,key);
        String fingerprint = digest(request);
        try {
            if (!previous.isEmpty()) {
                if (!fingerprint.equals(previous.getFirst().get("request_digest"))) throw conflict("Idempotency key was used for another request.");
                return json.readValue((String)previous.getFirst().get("response"), new TypeReference<>() { });
            }
            var response = operation.get();
            sql.update("INSERT INTO admin_commands(actor_id,command_key,request_digest,response) VALUES (?,?,?,?::jsonb)",
                    actor,key,fingerprint,json.writeValueAsString(response));
            return response;
        } catch (java.io.IOException failure) { throw new IllegalStateException("Command response serialization failed."); }
    }

    public static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException failure) { throw new IllegalStateException("SHA-256 unavailable"); }
    }

    public static ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST,message); }
    public static ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT,message); }
}

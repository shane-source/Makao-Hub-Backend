package com.makaohub.backend.support;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/** Jirani, the Makao Hub help assistant. Signed-in users only. */
@RestController
@RequestMapping("/api/v1/support")
public class SupportController {

    /** One earlier message in the conversation. The role is "user" or "assistant". */
    public record Turn(String role, String content) {
    }

    public record ChatRequest(List<Turn> messages) {
    }

    private final JiraniService jirani;

    public SupportController(JiraniService jirani) {
        this.jirani = jirani;
    }

    @PostMapping("/chat")
    public Map<String, String> chat(@AuthenticationPrincipal Jwt jwt, @RequestBody ChatRequest request) {
        if (jwt == null || jwt.getSubject() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Please sign in again.");
        }
        List<Turn> turns = request == null ? null : request.messages();
        return Map.of("reply", jirani.reply(jwt.getSubject(), roleOf(jwt), turns));
    }

    private static String roleOf(Jwt jwt) {
        Object claim = jwt.getClaim("role");
        if (claim == null) claim = jwt.getClaim("roles");
        String text = claim == null ? "" : claim.toString().toUpperCase();
        if (text.contains("LANDLORD")) return "landlord";
        if (text.contains("SUBLESSOR")) return "sublessor";
        if (text.contains("RENTER")) return "renter";
        return "user";
    }
}

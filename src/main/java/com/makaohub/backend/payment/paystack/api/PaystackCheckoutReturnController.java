package com.makaohub.backend.payment.paystack.api;

import com.makaohub.backend.payment.paystack.config.PaystackProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public, data-free navigation page. Visiting it is never evidence of successful payment. */
@RestController
@Profile("paystack")
public class PaystackCheckoutReturnController {
    @GetMapping(value = PaystackProperties.RETURN_PATH, produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> page(HttpServletRequest request) {
        // Paystack appends a reference. Do not reflect, store, log, forward or use it to change payment state.
        var response = ResponseEntity.status(request.getQueryString() == null ? HttpStatus.OK : HttpStatus.SEE_OTHER)
                .cacheControl(CacheControl.noStore())
                .header("Referrer-Policy", "no-referrer")
                .header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; frame-ancestors 'none'; form-action 'none'");
        if (request.getQueryString() != null) {
            return response.header(HttpHeaders.LOCATION, PaystackProperties.RETURN_PATH).build();
        }
        return response.contentType(MediaType.TEXT_HTML).body("""
                <!doctype html>
                <html lang="en"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <meta name="referrer" content="no-referrer">
                <title>Return to Makao Hub</title>
                <style>
                  :root { color-scheme: light dark; --bg:#faf7f1; --card:#fff; --text:#403b36; --brand:#573b2d; --on-brand:#fff; }
                  @media(prefers-color-scheme:dark) { :root { --bg:#181512; --card:#24201c; --text:#f4ede4; --brand:#ddba9e; --on-brand:#302014; } }
                  * { box-sizing:border-box; } body { margin:0; background:var(--bg); color:var(--text); font:1rem/1.6 system-ui,sans-serif; }
                  main { max-width:32rem; margin:8vh auto; padding:1.5rem; } section { background:var(--card); padding:1.5rem; border-radius:1.5rem; }
                  h1 { color:var(--brand); font-size:2rem; line-height:1.2; } a { display:block; padding:1rem; margin:1.5rem 0; min-height:48px; background:var(--brand); color:var(--on-brand); border-radius:.75rem; text-align:center; text-decoration:none; font-weight:700; }
                  a:focus-visible { outline:3px solid var(--brand); outline-offset:4px; }
                </style></head><body><main><strong>makao hub</strong><section>
                <h1>Continue in Makao Hub</h1>
                <p>Return to the app to check your Paystack TEST payment securely.</p>
                <a href="intent://payments/return#Intent;scheme=makaohub;package=com.makaohub.android;end">Return to Makao Hub</a>
                <p>This page does not confirm payment. Makao Hub will verify the result with the server.</p>
                <p>If the button does not open the app, switch to Makao Hub and tap Check Paystack payment.
                If the app restarted, sign in and open Payment history. Do not pay again while the payment is pending.</p>
                </section></main></body></html>
                """
        );
    }
}

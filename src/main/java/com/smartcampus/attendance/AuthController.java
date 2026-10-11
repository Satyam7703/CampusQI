package com.smartcampus.attendance;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/auth")
class AuthController {
  private static final Logger log = LoggerFactory.getLogger(AuthController.class);
  private final JdbcTemplate db;
  private final PasswordEncoder encoder;
  private final JavaMailSender mail;
  private final SecureRandom random = new SecureRandom();
  private final Map<String, WindowCounter> attempts = new ConcurrentHashMap<>();
  @Value("${spring.mail.host:}") private String smtpHost;
  @Value("${app.mail.from:no-reply@example.invalid}") private String from;
  @Value("${app.security.reset-token-minutes:15}") private long resetMinutes;

  AuthController(JdbcTemplate db, PasswordEncoder encoder, JavaMailSender mail) { this.db=db; this.encoder=encoder; this.mail=mail; }

  @PostMapping("/login")
  ResponseEntity<?> login(@RequestBody Map<String,Object> body, HttpServletRequest request) {
    String ip=clientIp(request); limit("login:"+ip, 10, Duration.ofMinutes(5));
    String email=value(body,"email").toLowerCase(Locale.ROOT), password=value(body,"password");
    if (email.isBlank() || password.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Email and password are required");
    List<Map<String,Object>> rows=db.queryForList("SELECT id,roll_no,full_name,email,course,branch_name,semester,section_name,status,password_hash FROM students WHERE LOWER(email)=LOWER(?)",email);
    if(rows.isEmpty() || rows.get(0).get("PASSWORD_HASH")==null || !encoder.matches(password,String.valueOf(rows.get(0).get("PASSWORD_HASH")))) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid email or password. Ask your college admin to activate your account if this is your first login.");
    }
    Map<String,Object> u=rows.get(0);
    if(!"ACTIVE".equalsIgnoreCase(String.valueOf(u.get("STATUS")))) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Account is not active");
    return ResponseEntity.ok(Map.of("message","Login verified","student",Map.of("id",u.get("ID"),"rollNo",u.get("ROLL_NO"),"fullName",u.get("FULL_NAME"),"email",u.get("EMAIL"),"course",u.get("COURSE"),"branch",u.get("BRANCH_NAME"),"semester",u.get("SEMESTER"),"section",u.get("SECTION_NAME")),"note","This prototype login endpoint does not yet issue a production access token or protect all ERP routes."));
  }

  @PostMapping("/forgot-password")
  ResponseEntity<?> forgot(@RequestBody Map<String,Object> body, HttpServletRequest request) {
    String ip=clientIp(request); limit("forgot:"+ip, 5, Duration.ofMinutes(15));
    String email=value(body,"email").toLowerCase(Locale.ROOT);
    if(email.isBlank() || email.length()>160) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Enter a valid email address");
    // SMTP readiness is checked before account lookup so this response cannot reveal whether an email exists.
    if(smtpHost==null || smtpHost.isBlank() || smtpHost.contains("example.invalid")) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"SMTP is not configured. Set SMTP_HOST, SMTP_USERNAME, SMTP_PASSWORD and SMTP_FROM, then restart the app.");
    }
    // Always return the same public response to prevent account enumeration.
    List<Map<String,Object>> rows=db.queryForList("SELECT email FROM students WHERE LOWER(email)=LOWER(?) AND status='ACTIVE'",email);
    if(!rows.isEmpty()) {
      byte[] bytes=new byte[32]; random.nextBytes(bytes); String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
      String hash=sha256(token); LocalDateTime expires=LocalDateTime.now().plusMinutes(resetMinutes);
      db.update("UPDATE password_reset_tokens SET used=TRUE WHERE LOWER(email)=LOWER(?) AND used=FALSE",email);
      db.update("INSERT INTO password_reset_tokens(email,token_hash,expires_at,used) VALUES(?,?,?,FALSE)",email,hash,Timestamp.valueOf(expires));
      try {
        SimpleMailMessage msg=new SimpleMailMessage(); msg.setFrom(from); msg.setTo(email); msg.setSubject("Smart Campus ERP — Password reset");
        msg.setText("We received a request to set or reset your Smart Campus ERP password.\n\nOpen the password reset page on the same server and enter this one-time token:\n\n"+token+"\n\nThis token expires in "+resetMinutes+" minutes and can only be used once. If you did not request this, ignore this email.");
        mail.send(msg);
      } catch(MailException ex) {
        log.error("Password reset email delivery failed: {}", ex.getMessage(), ex);
        db.update("UPDATE password_reset_tokens SET used=TRUE WHERE token_hash=?",hash);
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Could not send reset email. Check SMTP settings and app logs.");
      }
    }
    return ResponseEntity.ok(Map.of("message","If an active account matches that email, password reset instructions have been sent."));
  }

  @PostMapping("/reset-password")
  ResponseEntity<?> reset(@RequestBody Map<String,Object> body, HttpServletRequest request) {
    limit("reset:"+clientIp(request), 8, Duration.ofMinutes(15));
    String token=value(body,"token"), password=value(body,"newPassword");
    if(token.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Reset token is required");
    if(!password.matches("^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[^A-Za-z0-9]).{10,72}$")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Password must be 10-72 characters with upper/lowercase, a number and a symbol");
    List<Map<String,Object>> rows=db.queryForList("SELECT id,email,expires_at,used FROM password_reset_tokens WHERE token_hash=?",sha256(token));
    if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid or expired reset token");
    Map<String,Object> r=rows.get(0); Timestamp expiry=(Timestamp)r.get("EXPIRES_AT");
    if(Boolean.TRUE.equals(r.get("USED")) || expiry.toLocalDateTime().isBefore(LocalDateTime.now())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid or expired reset token");
    String email=String.valueOf(r.get("EMAIL"));
    int n=db.update("UPDATE students SET password_hash=? WHERE LOWER(email)=LOWER(?) AND status='ACTIVE'",encoder.encode(password),email);
    if(n==0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Account is unavailable");
    db.update("UPDATE password_reset_tokens SET used=TRUE WHERE LOWER(email)=LOWER(?)",email);
    db.update("INSERT INTO audit_logs(action_name,actor,detail) VALUES(?,?,?)","PASSWORD_RESET",email,"Password reset completed via email token");
    return ResponseEntity.ok(Map.of("message","Password reset successful. You can now log in."));
  }

  private void limit(String key,int max,Duration window) {
    long now=System.currentTimeMillis(); WindowCounter c=attempts.compute(key,(k,old)->old==null||now-old.start>window.toMillis()?new WindowCounter(now,1):new WindowCounter(old.start,old.count+1));
    if(c.count>max) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"Too many attempts. Wait a few minutes and try again.");
  }
  private record WindowCounter(long start,int count){}
  private String clientIp(HttpServletRequest r){ return r.getRemoteAddr()==null?"unknown":r.getRemoteAddr(); }
  private String value(Map<String,Object> b,String k){ Object v=b.get(k); return v==null?"":String.valueOf(v).trim(); }
  private String sha256(String v){ try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(v.getBytes(StandardCharsets.UTF_8))); } catch(Exception e){ throw new IllegalStateException(e); } }
}

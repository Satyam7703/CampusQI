package com.smartcampus.attendance;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
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
  private final JdbcTemplate db;
  private final PasswordEncoder encoder;
  private final JavaMailSender mail;
  private final FaceAuthService faceAuth;
  private final SecureRandom random = new SecureRandom();
  private final Map<String, WindowCounter> attempts = new ConcurrentHashMap<>();
  @Value("${spring.mail.host:}") private String smtpHost;
  @Value("${spring.mail.username:}") private String smtpUsername;
  @Value("${spring.mail.password:}") private String smtpPassword;
  @Value("${app.mail.from:no-reply@example.invalid}") private String from;
  @Value("${app.security.reset-token-minutes:15}") private long resetMinutes;
  @Value("${app.security.reset-token-expose:false}") private boolean exposeResetToken;
  @Value("${app.public-base-url:http://localhost:8081}") private String publicBaseUrl;

  AuthController(JdbcTemplate db, PasswordEncoder encoder, JavaMailSender mail, FaceAuthService faceAuth) { this.db=db; this.encoder=encoder; this.mail=mail; this.faceAuth=faceAuth; }

  @PostMapping("/login")
  ResponseEntity<?> login(@RequestBody Map<String,Object> body, HttpServletRequest request) {
    String ip=clientIp(request); limit("login:"+ip, 10, Duration.ofMinutes(5));
    String email=value(body,"email").toLowerCase(Locale.ROOT), password=value(body,"password");
    if (email.isBlank() || password.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Email and password are required");
    List<Map<String,Object>> rows=db.queryForList("SELECT id,roll_no,full_name,email,course,branch_name,semester,section_name,status,password_hash FROM students WHERE LOWER(email)=LOWER(?)",email);
    if(rows.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"No student account was found for this email. Check the college email or ask your admin to register/activate your account.");
    }
    if(rows.get(0).get("PASSWORD_HASH")==null || String.valueOf(rows.get(0).get("PASSWORD_HASH")).isBlank()) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"No password has been set for this account yet. Select Forgot password to receive an OTP and create your password.");
    }
    if(!encoder.matches(password,String.valueOf(rows.get(0).get("PASSWORD_HASH")))) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Incorrect email or password. If you forgot your password, use Forgot password.");
    }
    Map<String,Object> u=rows.get(0);
    if(!"ACTIVE".equalsIgnoreCase(String.valueOf(u.get("STATUS")))) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Account is not active");
    long studentId=((Number)u.get("ID")).longValue();
    return ResponseEntity.ok(Map.of("message","Credentials verified","accessToken",faceAuth.issuePasswordSession(studentId),"student",Map.of("id",studentId,"rollNo",u.get("ROLL_NO"),"fullName",u.get("FULL_NAME"),"email",u.get("EMAIL"),"course",u.get("COURSE"),"branch",u.get("BRANCH_NAME"),"semester",u.get("SEMESTER"),"section",u.get("SECTION_NAME")),"note","Verify your face from the student dashboard before marking attendance."));
  }

  @PostMapping("/forgot-password")
  ResponseEntity<?> forgot(@RequestBody Map<String,Object> body, HttpServletRequest request) {
    limit("forgot:"+clientIp(request), 5, Duration.ofMinutes(15));
    String email=value(body,"email").toLowerCase(Locale.ROOT);
    String accountType=accountType(body);
    if(email.isBlank() || email.length()>160 || !email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Enter a valid registered college email address");
    boolean smtpReady = smtpHost!=null && !smtpHost.isBlank() && !smtpHost.contains("example.invalid")
      && smtpUsername!=null && !smtpUsername.isBlank()
      && smtpPassword!=null && !smtpPassword.isBlank()
      && from!=null && !from.isBlank() && !from.contains("example.invalid");
    String accountTable="STAFF".equals(accountType)?"staff_users":"students";
    List<Map<String,Object>> rows=db.queryForList("SELECT email FROM "+accountTable+" WHERE LOWER(email)=LOWER(?) AND status='ACTIVE'",email);
    // Keep the public response generic so the endpoint does not reveal whether an account exists.
    if(rows.isEmpty()) return ResponseEntity.ok(Map.of("message","If an active account matches that email, an OTP has been sent."));
    if(!smtpReady && !exposeResetToken)
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"OTP email is not configured. Configure SMTP in application.properties/environment. For local testing only, set RESET_TOKEN_EXPOSE=true and restart.");
    String otp=String.format(Locale.ROOT,"%06d",random.nextInt(1_000_000));
    String otpHash=sha256(email+":"+otp);
    LocalDateTime expires=LocalDateTime.now().plusMinutes(10);
    db.update("UPDATE password_reset_otps SET used=TRUE WHERE LOWER(email)=LOWER(?) AND account_type=? AND used=FALSE",email,accountType);
    db.update("INSERT INTO password_reset_otps(email,account_type,otp_hash,expires_at,used,attempts) VALUES(?,?,?,?,FALSE,0)",email,accountType,otpHash,Timestamp.valueOf(expires));
    if(smtpReady) {
      try {
        SimpleMailMessage msg=new SimpleMailMessage(); msg.setFrom(from); msg.setTo(email);
        msg.setSubject("Smart Campus ERP — Password reset OTP");
        msg.setText("Your Smart Campus ERP password reset OTP is: "+otp+"\n\nEnter this 6-digit code on the Forgot Password page. It expires in 10 minutes and can only be used once.\n\nIf you did not request this, ignore this email.");
        mail.send(msg);
      } catch(MailException ex) {
        db.update("UPDATE password_reset_otps SET used=TRUE WHERE otp_hash=?",otpHash);
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Could not send OTP email. Check SMTP settings and app logs.");
      }
      return ResponseEntity.ok(Map.of("message","If an active account matches that email, an OTP has been sent. Check your inbox and spam folder."));
    }
    return ResponseEntity.ok(Map.of("message","LOCAL DEVELOPMENT ONLY: use this OTP for testing. Do not enable this on a public server.","developmentOtp",otp));
  }

  @PostMapping("/verify-reset-otp")
  ResponseEntity<?> verifyResetOtp(@RequestBody Map<String,Object> body, HttpServletRequest request) {
    limit("verify-otp:"+clientIp(request), 8, Duration.ofMinutes(10));
    String email=value(body,"email").toLowerCase(Locale.ROOT), otp=value(body,"otp");
    String accountType=accountType(body);
    if(email.isBlank() || !otp.matches("^\\d{6}$")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Enter the registered email and 6-digit OTP");
    List<Map<String,Object>> rows=db.queryForList("SELECT id,otp_hash,expires_at,used,attempts FROM password_reset_otps WHERE LOWER(email)=LOWER(?) AND account_type=? ORDER BY id DESC LIMIT 1",email,accountType);
    if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"OTP is invalid or expired. Request a new OTP.");
    Map<String,Object> row=rows.get(0); long id=((Number)row.get("ID")).longValue();
    Timestamp expiry=(Timestamp)row.get("EXPIRES_AT"); boolean used=Boolean.TRUE.equals(row.get("USED")); int attemptsCount=((Number)row.get("ATTEMPTS")).intValue();
    if(used || expiry.toLocalDateTime().isBefore(LocalDateTime.now()) || attemptsCount>=5) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"OTP is invalid or expired. Request a new OTP.");
    db.update("UPDATE password_reset_otps SET attempts=attempts+1 WHERE id=?",id);
    if(!MessageDigest.isEqual(String.valueOf(row.get("OTP_HASH")).getBytes(StandardCharsets.UTF_8),sha256(email+":"+otp).getBytes(StandardCharsets.UTF_8)))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Incorrect OTP. Please check the code and try again.");
    db.update("UPDATE password_reset_otps SET used=TRUE WHERE id=?",id);
    byte[] bytes=new byte[32]; random.nextBytes(bytes); String resetToken=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    db.update("UPDATE password_reset_tokens SET used=TRUE WHERE LOWER(email)=LOWER(?) AND account_type=? AND used=FALSE",email,accountType);
    db.update("INSERT INTO password_reset_tokens(email,account_type,token_hash,expires_at,used) VALUES(?,?,?,?,FALSE)",email,accountType,sha256(resetToken),Timestamp.valueOf(LocalDateTime.now().plusMinutes(10)));
    return ResponseEntity.ok(Map.of("message","OTP verified. Set your new password.","resetToken",resetToken));
  }

  @PostMapping("/reset-password")
  ResponseEntity<?> reset(@RequestBody Map<String,Object> body, HttpServletRequest request) {
    limit("reset:"+clientIp(request), 8, Duration.ofMinutes(15));
    String accountType=accountType(body);
    String token=value(body,"token"), password=value(body,"newPassword");
    if(token.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Reset token is required");
    if(!password.matches("^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[^A-Za-z0-9]).{10,72}$")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Password must be 10-72 characters with upper/lowercase, a number and a symbol");
    List<Map<String,Object>> rows=db.queryForList("SELECT id,email,account_type,expires_at,used FROM password_reset_tokens WHERE token_hash=? AND account_type=?",sha256(token),accountType);
    if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid or expired reset token");
    Map<String,Object> r=rows.get(0); Timestamp expiry=(Timestamp)r.get("EXPIRES_AT");
    if(Boolean.TRUE.equals(r.get("USED")) || expiry.toLocalDateTime().isBefore(LocalDateTime.now())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid or expired reset token");
    String email=String.valueOf(r.get("EMAIL"));
    String accountTable="STAFF".equals(accountType)?"staff_users":"students";
    int n=db.update("UPDATE "+accountTable+" SET password_hash=? WHERE LOWER(email)=LOWER(?) AND status='ACTIVE'",encoder.encode(password),email);
    if(n==0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Account is unavailable");
    db.update("UPDATE password_reset_tokens SET used=TRUE WHERE LOWER(email)=LOWER(?) AND account_type=?",email,accountType);
    db.update("INSERT INTO audit_logs(action_name,actor,detail) VALUES(?,?,?)","PASSWORD_RESET",email,"Password reset completed via email token");
    return ResponseEntity.ok(Map.of("message","Password reset successful. You can now log in."));
  }

  private void limit(String key,int max,Duration window) {
    long now=System.currentTimeMillis(); WindowCounter c=attempts.compute(key,(k,old)->old==null||now-old.start>window.toMillis()?new WindowCounter(now,1):new WindowCounter(old.start,old.count+1));
    if(c.count>max) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"Too many attempts. Wait a few minutes and try again.");
  }
  private record WindowCounter(long start,int count){}
  private String clientIp(HttpServletRequest r){ return r.getRemoteAddr()==null?"unknown":r.getRemoteAddr(); }
  private String accountType(Map<String,Object> body){ String type=value(body,"accountType").toUpperCase(Locale.ROOT); return "STAFF".equals(type)?"STAFF":"STUDENT"; }
  private String value(Map<String,Object> b,String k){ Object v=b.get(k); return v==null?"":String.valueOf(v).trim(); }
  private String sha256(String v){ try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(v.getBytes(StandardCharsets.UTF_8))); } catch(Exception e){ throw new IllegalStateException(e); } }
}

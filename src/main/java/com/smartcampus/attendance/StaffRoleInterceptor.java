package com.smartcampus.attendance;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
class StaffRoleInterceptor implements HandlerInterceptor {
  private static final Pattern STUDENT_REPORT = Pattern.compile("/api/students/(\\d+)/(attendance|attendance-summary|classmates|marks|timetable)");
  private final JdbcTemplate db;
  private final FaceAuthService faceAuth;

  StaffRoleInterceptor(JdbcTemplate db, FaceAuthService faceAuth) { this.db=db; this.faceAuth=faceAuth; }

  @Override public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) throws Exception {
    String path=req.getRequestURI();
    if(!path.startsWith("/api/") || path.equals("/api/health") || path.startsWith("/api/auth/") || path.startsWith("/api/face/") || path.startsWith("/api/staff-auth/")) return true;

    Matcher report=STUDENT_REPORT.matcher(path);
    if(report.matches()) {
      if(!"GET".equalsIgnoreCase(req.getMethod())) return deny(res,405,"Student reports are read-only.");
      long requestedId=Long.parseLong(report.group(1));
      long sessionStudentId;
      try { sessionStudentId=faceAuth.authenticatedStudentId(bearer(req)); }
      catch(org.springframework.web.server.ResponseStatusException ex) { return deny(res,ex.getStatusCode().value(),ex.getReason()); }
      if(sessionStudentId!=requestedId) return deny(res,403,"You can only view your own attendance records.");
      Integer active=db.queryForObject("SELECT COUNT(*) FROM students WHERE id=? AND status='ACTIVE'",Integer.class,sessionStudentId);
      if(active==null || active==0) return deny(res,403,"Student account is inactive.");
      req.setAttribute("authenticatedStudentId",sessionStudentId);
      return true;
    }

    if(path.equals("/api/student/sessions") || path.equals("/api/student/timetable")) {
      if(!"GET".equalsIgnoreCase(req.getMethod())) return deny(res,405,"Student session listing is read-only.");
      long studentId;
      try { studentId=faceAuth.authenticatedStudentId(bearer(req)); }
      catch(org.springframework.web.server.ResponseStatusException ex) { return deny(res,ex.getStatusCode().value(),ex.getReason()); }
      Integer active=db.queryForObject("SELECT COUNT(*) FROM students WHERE id=? AND status='ACTIVE'",Integer.class,studentId);
      if(active==null || active==0) return deny(res,403,"Student account is inactive.");
      req.setAttribute("authenticatedStudentId",studentId);
      return true;
    }

    if(path.equals("/api/student/notifications")) {
      if(!"GET".equalsIgnoreCase(req.getMethod())) return deny(res,405,"Student notifications are read-only.");
      long studentId;
      try { studentId=faceAuth.authenticatedStudentId(bearer(req)); }
      catch(org.springframework.web.server.ResponseStatusException ex) { return deny(res,ex.getStatusCode().value(),ex.getReason()); }
      Integer active=db.queryForObject("SELECT COUNT(*) FROM students WHERE id=? AND status='ACTIVE'",Integer.class,studentId);
      if(active==null || active==0) return deny(res,403,"Student account is inactive.");
      req.setAttribute("authenticatedStudentId",studentId);
      return true;
    }
    if(path.equals("/api/student/fees") || path.equals("/api/student/fees/verify") || path.matches("/api/student/fees/\\d+/order")) {
      if(!("GET".equalsIgnoreCase(req.getMethod()) || "POST".equalsIgnoreCase(req.getMethod()))) return deny(res,405,"Invalid fee request.");
      try { long studentId=faceAuth.authenticatedStudentId(bearer(req)); Integer active=db.queryForObject("SELECT COUNT(*) FROM students WHERE id=? AND status='ACTIVE'",Integer.class,studentId); if(active==null||active==0)return deny(res,403,"Student account is inactive."); req.setAttribute("authenticatedStudentId",studentId); return true; }
      catch(org.springframework.web.server.ResponseStatusException ex){return deny(res,ex.getStatusCode().value(),ex.getReason());}
    }
    if(path.equals("/api/notices") && "GET".equalsIgnoreCase(req.getMethod())) {
      try {
        long studentId=faceAuth.authenticatedStudentId(bearer(req));
        req.setAttribute("authenticatedStudentId",studentId);
        return true;
      } catch(Exception ignored) {
        // A staff token is checked by the common staff authorization below.
      }
    }
    if(path.equals("/api/marks/settings") && "GET".equalsIgnoreCase(req.getMethod())) {
      try {
        long id=faceAuth.authenticatedStudentId(bearer(req));
        Integer active=db.queryForObject("SELECT COUNT(*) FROM students WHERE id=? AND status='ACTIVE'",Integer.class,id);
        if(active!=null && active>0) { req.setAttribute("authenticatedStudentId",id); return true; }
      } catch(Exception ignored) { }
    }

    String token=StaffAuthController.bearer(req);
    if(token.isBlank()) return deny(res,401,"Staff login required");
    List<Map<String,Object>> rows=db.queryForList("SELECT ss.user_id,ss.role,ss.expires_at,su.email,su.status FROM staff_sessions ss JOIN staff_users su ON su.id=ss.user_id WHERE ss.token_hash=? AND ss.revoked=FALSE",StaffAuthController.sha(token));
    if(rows.isEmpty()) return deny(res,401,"Session is invalid. Sign in again.");
    Map<String,Object> u=rows.get(0); Timestamp expiry=(Timestamp)u.get("EXPIRES_AT");
    if(!"ACTIVE".equalsIgnoreCase(String.valueOf(u.get("STATUS"))) || expiry.toLocalDateTime().isBefore(LocalDateTime.now())) return deny(res,401,"Session expired or account inactive. Sign in again.");
    String role=String.valueOf(u.get("ROLE")).toUpperCase(Locale.ROOT),method=req.getMethod();
    boolean teacherRead=role.equals("TEACHER") && method.equals("GET") && (path.equals("/api/timetable") || path.equals("/api/classrooms") || path.equals("/api/sessions") || path.equals("/api/attendance") || path.equals("/api/teacher/students") || path.equals("/api/notices") || path.equals("/api/reports/analytics") || path.equals("/api/reports/export.csv") || path.equals("/api/reports/export.xlsx") || path.equals("/api/reports/export.pdf"));
    boolean teacherAction=role.equals("TEACHER") && ((path.equals("/api/sessions/start") && method.equals("POST")) || (path.matches("/api/sessions/\\d+/(close|delete)") && method.equals("POST")) || (path.equals("/api/marks") && method.equals("POST")) || (path.equals("/api/teacher/marks") && method.equals("GET")) || (path.matches("/api/sessions/\\d+/attendance/\\d+") && method.equals("POST")));
    if(!role.equals("ADMIN") && !teacherRead && !teacherAction) return deny(res,403,"Your staff role is not allowed to access this resource.");
    req.setAttribute("staffUserId",u.get("USER_ID")); req.setAttribute("staffRole",role); req.setAttribute("staffEmail",u.get("EMAIL"));
    return true;
  }


  private String bearer(HttpServletRequest req) {
    String header=req.getHeader("Authorization");
    return header!=null && header.regionMatches(true,0,"Bearer ",0,7) ? header.substring(7).trim() : "";
  }
  private boolean deny(HttpServletResponse r,int status,String msg) throws Exception {
    r.setStatus(status); r.setContentType("application/json");
    String safe=msg==null?"Unauthorized":msg.replace("\\","\\\\").replace("\"","\\\"");
    r.getWriter().write("{\"message\":\""+safe+"\"}"); return false;
  }
}

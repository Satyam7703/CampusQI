package com.smartcampus.attendance;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
class NotificationController {
  private final JdbcTemplate db;
  private final AttendanceNotificationService service;
  NotificationController(JdbcTemplate db, AttendanceNotificationService service) { this.db=db; this.service=service; }

  @GetMapping("/api/student/notifications")
  List<Map<String,Object>> studentNotifications(HttpServletRequest request) {
    long studentId=((Number)request.getAttribute("authenticatedStudentId")).longValue();
    return db.queryForList("SELECT id,title,message,alert_type alertType,email_status emailStatus,created_at createdAt FROM attendance_notifications WHERE student_id=? ORDER BY created_at DESC,id DESC LIMIT 100",studentId);
  }

  @GetMapping("/api/notifications")
  List<Map<String,Object>> adminNotifications(HttpServletRequest request) {
    requireAdmin(request);
    return db.queryForList("SELECT n.id,n.student_id studentId,s.full_name studentName,s.roll_no rollNo,n.title,n.message,n.alert_type alertType,n.recipient_email recipientEmail,n.email_status emailStatus,n.created_at createdAt FROM attendance_notifications n JOIN students s ON s.id=n.student_id ORDER BY n.created_at DESC,n.id DESC LIMIT 250");
  }

  @PostMapping("/api/notifications/scan-low-attendance")
  Map<String,Object> scan(HttpServletRequest request) {
    requireAdmin(request);
    Map<String,Object> result=new LinkedHashMap<>(service.scanLowAttendance());
    db.update("INSERT INTO audit_logs(action_name,actor,detail) VALUES(?,?,?)","LOW_ATTENDANCE_NOTIFICATION_SCAN",String.valueOf(request.getAttribute("staffEmail")),"date="+result.get("scanDate")+", created="+result.get("notificationsCreated"));
    return result;
  }

  private void requireAdmin(HttpServletRequest request) {
    if(!"ADMIN".equals(String.valueOf(request.getAttribute("staffRole"))))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Only an administrator can view or trigger notification scans.");
  }
}

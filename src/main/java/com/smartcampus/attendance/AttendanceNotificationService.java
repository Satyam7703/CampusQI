package com.smartcampus.attendance;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
class AttendanceNotificationService {
  private final JdbcTemplate db;
  private final JavaMailSender mailSender;
  private final String mailHost;
  private final String mailFrom;
  private final double threshold;

  AttendanceNotificationService(JdbcTemplate db, JavaMailSender mailSender,
      @Value("${spring.mail.host:}") String mailHost,
      @Value("${app.mail.from:no-reply@example.invalid}") String mailFrom,
      @Value("${app.notifications.low-attendance-threshold:75}") double threshold) {
    this.db=db; this.mailSender=mailSender; this.mailHost=mailHost; this.mailFrom=mailFrom;
    this.threshold=(threshold>0 && threshold<=100)?threshold:75.0;
  }

  @Scheduled(cron="${app.notifications.cron:0 0 8 * * *}", zone="${app.notifications.time-zone:Asia/Kolkata}")
  public void scheduledLowAttendanceScan() {
    try { scanLowAttendance(); }
    catch (Exception ex) { System.err.println("Scheduled attendance notification scan failed: "+ex.getMessage()); }
  }

  Map<String,Object> scanLowAttendance() {
    LocalDate today=LocalDate.now();
    List<Map<String,Object>> low=db.queryForList(
      "SELECT st.id studentId, st.full_name studentName, st.roll_no rollNo, st.email email, " +
      "COUNT(DISTINCT s.id) eligibleSessions, COUNT(DISTINCT CASE WHEN ar.status='PRESENT' THEN s.id END) presentSessions, " +
      "CASE WHEN COUNT(DISTINCT s.id)=0 THEN 0 ELSE ROUND(100.0*COUNT(DISTINCT CASE WHEN ar.status='PRESENT' THEN s.id END)/COUNT(DISTINCT s.id),2) END attendancePercentage " +
      "FROM students st JOIN timetable t ON t.course=st.course AND t.branch_name=st.branch_name AND t.semester=st.semester AND t.section_name=st.section_name " +
      "JOIN attendance_sessions s ON s.timetable_id=t.id AND s.session_date<=? " +
      "LEFT JOIN attendance_records ar ON ar.session_id=s.id AND ar.student_id=st.id " +
      "WHERE st.status='ACTIVE' GROUP BY st.id,st.full_name,st.roll_no,st.email " +
      "HAVING COUNT(DISTINCT s.id)>0 AND 100.0*COUNT(DISTINCT CASE WHEN ar.status='PRESENT' THEN s.id END)/COUNT(DISTINCT s.id)<? " +
      "ORDER BY attendancePercentage,st.full_name", java.sql.Date.valueOf(today), threshold);

    int created=0, emailed=0, failed=0, existing=0;
    String period=today.toString();
    for(Map<String,Object> student:low) {
      long studentId=((Number)value(student,"STUDENTID","studentId")).longValue();
      String name=String.valueOf(value(student,"STUDENTNAME","studentName"));
      String roll=String.valueOf(value(student,"ROLLNO","rollNo"));
      String email=asText(value(student,"EMAIL","email"));
      double rate=((Number)value(student,"ATTENDANCEPERCENTAGE","attendancePercentage")).doubleValue();
      String title="Attendance is below "+format(threshold)+"%";
      String message="Hi "+name+" ("+roll+"), your recorded attendance is "+format(rate)+"% across "+value(student,"ELIGIBLESESSIONS","eligibleSessions")+" class sessions. The current college alert threshold is "+format(threshold)+"%. Please review your attendance with your faculty if you think a record is missing.";
      String status=StringUtils.hasText(email)&&StringUtils.hasText(mailHost)?"PENDING":"IN_APP_ONLY";
      try {
        db.update("INSERT INTO attendance_notifications(student_id,alert_type,period_key,title,message,recipient_email,email_status) VALUES(?,?,?,?,?,?,?)",
          studentId,"LOW_ATTENDANCE",period,title,message,StringUtils.hasText(email)?email:null,status);
        created++;
      } catch(DataIntegrityViolationException duplicate) { existing++; continue; }

      if("PENDING".equals(status)) {
        try {
          SimpleMailMessage mail=new SimpleMailMessage();
          mail.setFrom(mailFrom); mail.setTo(email); mail.setSubject("CampusIQ attendance alert"); mail.setText(message);
          mailSender.send(mail);
          db.update("UPDATE attendance_notifications SET email_status='SENT' WHERE student_id=? AND alert_type=? AND period_key=?",studentId,"LOW_ATTENDANCE",period);
          emailed++;
        } catch(Exception ex) {
          db.update("UPDATE attendance_notifications SET email_status='FAILED' WHERE student_id=? AND alert_type=? AND period_key=?",studentId,"LOW_ATTENDANCE",period);
          failed++;
          System.err.println("Attendance alert email failed for studentId="+studentId+": "+ex.getMessage());
        }
      }
    }
    Map<String,Object> result=new LinkedHashMap<>();
    result.put("scanDate",period); result.put("threshold",threshold); result.put("studentsBelowThreshold",low.size());
    result.put("notificationsCreated",created); result.put("emailsSent",emailed); result.put("emailFailures",failed); result.put("alreadyNotifiedToday",existing);
    result.put("emailConfigured",StringUtils.hasText(mailHost));
    return result;
  }

  private Object value(Map<String,Object> row,String upper,String lower) { return row.containsKey(upper)?row.get(upper):row.get(lower); }
  private String asText(Object value) { return value==null?"":String.valueOf(value).trim(); }
  private String format(double n) { return n==(long)n?Long.toString((long)n):String.format(java.util.Locale.ROOT,"%.2f",n); }
}

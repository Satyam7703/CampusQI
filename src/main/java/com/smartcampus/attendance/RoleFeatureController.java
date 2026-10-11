package com.smartcampus.attendance;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

/** Role-scoped student/teacher features. Authorization is enforced by StaffRoleInterceptor. */
@RestController
@RequestMapping("/api")
class RoleFeatureController {
  private final JdbcTemplate db;
  RoleFeatureController(JdbcTemplate db) { this.db = db; }

  @GetMapping("/students/{id}/classmates")
  List<Map<String,Object>> classmates(@PathVariable long id) {
    return db.queryForList("SELECT id,roll_no rollNo,full_name fullName,email,course,branch_name branch,semester,section_name section FROM students WHERE status='ACTIVE' AND branch_name=(SELECT branch_name FROM students WHERE id=?) AND section_name=(SELECT section_name FROM students WHERE id=?) AND course=(SELECT course FROM students WHERE id=?) AND semester=(SELECT semester FROM students WHERE id=?) ORDER BY full_name", id,id,id,id);
  }

  @GetMapping("/students/{id}/marks")
  List<Map<String,Object>> studentMarks(@PathVariable long id) {
    return db.queryForList("SELECT m.id,m.assessment_type assessmentType,m.marks,t.subject_name subject,t.course,t.branch_name branch,t.semester,t.section_name section,m.updated_at updatedAt,(SELECT setting_value FROM institute_settings WHERE setting_key=CASE m.assessment_type WHEN 'CT' THEN 'CT_MAX_MARKS' WHEN 'SESSIONAL' THEN 'SESSIONAL_MAX_MARKS' WHEN 'PUT' THEN 'PUT_MAX_MARKS' ELSE 'MAX_MARKS' END) maxMarks FROM student_marks m JOIN timetable t ON t.id=m.timetable_id WHERE m.student_id=? ORDER BY t.subject_name,m.assessment_type", id);
  }

  @GetMapping("/marks/settings")
  Map<String,Object> marksSettings() {
    return markSettings();
  }

  @PatchMapping("/marks/settings")
  Map<String,Object> updateMarksSettings(@RequestBody Map<String,Object> body) {
    Map<String,String> keys=Map.of("CT","CT_MAX_MARKS","SESSIONAL","SESSIONAL_MAX_MARKS","PUT","PUT_MAX_MARKS");
    for(String type:keys.keySet()) {
      double max=number(body.get(type));
      if (max <= 0 || max > 1000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, type+" maximum marks must be between 1 and 1000.");
      String key=keys.get(type);
      int changed=db.update("UPDATE institute_settings SET setting_value=? WHERE setting_key=?",String.valueOf(max),key);
      if(changed==0) db.update("INSERT INTO institute_settings(setting_key,setting_value) VALUES(?,?)",key,String.valueOf(max));
    }
    return Map.of("settings",markSettings(),"message","Assessment maximum marks updated.");
  }

  @PostMapping("/marks")
  Map<String,Object> saveMarks(@RequestBody Map<String,Object> body, HttpServletRequest request) {
    long teacher = staffId(request);
    long timetable = longValue(body.get("timetableId")), student = longValue(body.get("studentId"));
    String type = String.valueOf(body.getOrDefault("assessmentType", "")).trim().toUpperCase(Locale.ROOT);
    if (!Set.of("CT","SESSIONAL","PUT").contains(type)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Assessment type must be CT, Sessional or PUT.");
    String maxKey=type.equals("CT")?"CT_MAX_MARKS":type.equals("SESSIONAL")?"SESSIONAL_MAX_MARKS":"PUT_MAX_MARKS";
    double max = Double.parseDouble(db.queryForObject("SELECT setting_value FROM institute_settings WHERE setting_key=?", String.class,maxKey));
    double marks = number(body.get("marks"));
    if (marks < 0 || marks > max) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Marks must be between 0 and the institute maximum.");
    Integer cohort = db.queryForObject("SELECT COUNT(*) FROM students s JOIN timetable t ON t.id=? WHERE s.id=? AND s.status='ACTIVE' AND UPPER(TRIM(s.course))=UPPER(TRIM(t.course)) AND UPPER(TRIM(s.branch_name))=UPPER(TRIM(t.branch_name)) AND s.semester=t.semester AND UPPER(TRIM(s.section_name))=UPPER(TRIM(t.section_name))", Integer.class, timetable, student);
    if (cohort == null || cohort == 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Student is not in this assigned class.");
    int changed=db.update("UPDATE student_marks SET marks=?,entered_by=?,updated_at=CURRENT_TIMESTAMP WHERE student_id=? AND timetable_id=? AND assessment_type=?",marks,teacher,student,timetable,type);
    if (changed==0) db.update("INSERT INTO student_marks(student_id,timetable_id,assessment_type,marks,entered_by,updated_at) VALUES(?,?,?,?,?,CURRENT_TIMESTAMP)",student,timetable,type,marks,teacher);
    return Map.of("message","Marks saved","studentId",student,"timetableId",timetable,"assessmentType",type,"marks",marks,"maxMarks",max);
  }

  @GetMapping("/teacher/marks")
  List<Map<String,Object>> teacherMarks(HttpServletRequest request) {
    long teacher=staffId(request);
    return db.queryForList("SELECT m.id,m.student_id studentId,s.roll_no rollNo,s.full_name studentName,m.timetable_id timetableId,t.subject_name subject,m.assessment_type assessmentType,m.marks,(SELECT setting_value FROM institute_settings WHERE setting_key=CASE m.assessment_type WHEN 'CT' THEN 'CT_MAX_MARKS' WHEN 'SESSIONAL' THEN 'SESSIONAL_MAX_MARKS' WHEN 'PUT' THEN 'PUT_MAX_MARKS' ELSE 'MAX_MARKS' END) maxMarks FROM student_marks m JOIN students s ON s.id=m.student_id JOIN timetable t ON t.id=m.timetable_id ORDER BY m.updated_at DESC");
  }
  private Map<String,Object> markSettings() {
    Map<String,Object> out=new LinkedHashMap<>();
    for(String type:List.of("CT","SESSIONAL","PUT")) {
      String key=type+"_MAX_MARKS";
      String value=db.queryForObject("SELECT setting_value FROM institute_settings WHERE setting_key=?",String.class,key);
      out.put(type,Double.parseDouble(value==null?"0":value));
    }
    return out;
  }

  @GetMapping("/teacher/students")
  List<Map<String,Object>> teacherStudents(@RequestParam(required=false) Long sessionId, HttpServletRequest request) {
    long teacher=staffId(request);
    if (sessionId != null) {
      return db.queryForList("SELECT DISTINCT s.id,s.roll_no rollNo,s.full_name fullName,s.course,s.branch_name branch,s.semester,s.section_name section " +
          "FROM students s JOIN attendance_sessions asess ON asess.id=? JOIN timetable t ON t.id=asess.timetable_id " +
          "WHERE s.status='ACTIVE' AND UPPER(TRIM(s.course))=UPPER(TRIM(t.course)) AND UPPER(TRIM(s.branch_name))=UPPER(TRIM(t.branch_name)) AND s.semester=t.semester AND UPPER(TRIM(s.section_name))=UPPER(TRIM(t.section_name)) " +
          "ORDER BY s.full_name", sessionId);
    }
    return db.queryForList("SELECT DISTINCT s.id,s.roll_no rollNo,s.full_name fullName,s.course,s.branch_name branch,s.semester,s.section_name section FROM students s JOIN timetable t ON UPPER(TRIM(t.course))=UPPER(TRIM(s.course)) AND UPPER(TRIM(t.branch_name))=UPPER(TRIM(s.branch_name)) AND t.semester=s.semester AND UPPER(TRIM(t.section_name))=UPPER(TRIM(s.section_name)) WHERE s.status='ACTIVE' ORDER BY s.full_name");
  }

  @PostMapping("/sessions/{sessionId}/attendance/{studentId}")
  Map<String,Object> manuallyMark(@PathVariable long sessionId, @PathVariable long studentId, @RequestBody Map<String,Object> body, HttpServletRequest request) {
    long teacher=staffId(request);
    String status=String.valueOf(body.getOrDefault("status","")).trim().toUpperCase(Locale.ROOT);
    if (!Set.of("PRESENT","ABSENT").contains(status)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Status must be Present or Absent.");
    Integer allowed=db.queryForObject("SELECT COUNT(*) FROM attendance_sessions s JOIN timetable t ON t.id=s.timetable_id JOIN students st ON st.id=? WHERE s.id=? AND st.status='ACTIVE' AND UPPER(TRIM(st.course))=UPPER(TRIM(t.course)) AND UPPER(TRIM(st.branch_name))=UPPER(TRIM(t.branch_name)) AND st.semester=t.semester AND UPPER(TRIM(st.section_name))=UPPER(TRIM(t.section_name))",Integer.class,studentId,sessionId);
    if (allowed==null || allowed==0) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Student is outside this class cohort.");
    int changed=db.update("UPDATE attendance_records SET status=?,verification_mode='MANUAL',marked_at=CURRENT_TIMESTAMP,note=? WHERE student_id=? AND session_id=?",status,"Marked by teacher",studentId,sessionId);
    if (changed==0) db.update("INSERT INTO attendance_records(student_id,session_id,status,verification_mode,marked_at,note) VALUES(?,?,?,'MANUAL',CURRENT_TIMESTAMP,?)",studentId,sessionId,status,"Marked by teacher");
    return Map.of("message","Attendance updated","studentId",studentId,"sessionId",sessionId,"status",status);
  }

  private long staffId(HttpServletRequest r) {
    if (!"TEACHER".equals(String.valueOf(r.getAttribute("staffRole")))) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Teacher access required.");
    return ((Number)r.getAttribute("staffUserId")).longValue();
  }
  private long longValue(Object value) { try { return Long.parseLong(String.valueOf(value)); } catch (Exception e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"A valid id is required."); } }
  private double number(Object value) { try { return Double.parseDouble(String.valueOf(value)); } catch (Exception e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"A valid numeric value is required."); } }
}

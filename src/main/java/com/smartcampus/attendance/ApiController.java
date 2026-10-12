package com.smartcampus.attendance;

import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.DuplicateKeyException;
import java.sql.*;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import java.sql.PreparedStatement;
import java.time.*;
import java.util.*;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
class ApiController {
  private final JdbcTemplate db; private final PasswordEncoder passwordEncoder;
  ApiController(JdbcTemplate db, PasswordEncoder passwordEncoder) { this.db = db; this.passwordEncoder=passwordEncoder; }

  @GetMapping("/health") Map<String,Object> health(){ return Map.of("status","UP","app","Smart Campus Attendance ERP","time",Instant.now().toString()); }
  @GetMapping("/dashboard") Map<String,Object> dashboard(){
    return Map.of("students",count("SELECT COUNT(*) FROM students WHERE status='ACTIVE'"),"classrooms",count("SELECT COUNT(*) FROM classrooms WHERE active=TRUE"),"sessionsToday",count("SELECT COUNT(*) FROM attendance_sessions WHERE session_date=CURRENT_DATE"),"presentToday",count("SELECT COUNT(*) FROM attendance_records ar JOIN attendance_sessions s ON s.id=ar.session_id WHERE s.session_date=CURRENT_DATE AND ar.status='PRESENT'"),"recent",db.queryForList("SELECT ar.id, st.full_name studentName, st.roll_no rollNo, t.subject_name subject, c.room_code room, ar.status, ar.marked_at markedAt FROM attendance_records ar JOIN students st ON st.id=ar.student_id JOIN attendance_sessions s ON s.id=ar.session_id JOIN timetable t ON t.id=s.timetable_id JOIN classrooms c ON c.id=s.classroom_id ORDER BY ar.marked_at DESC LIMIT 8"));
  }
  private long count(String q){ Long n=db.queryForObject(q,Long.class); return n==null?0:n; }

  @GetMapping("/students") List<Map<String,Object>> students(){ return db.queryForList("SELECT * FROM students ORDER BY course,branch_name,section_name,full_name"); }
  @PostMapping("/students") ResponseEntity<?> addStudent(@RequestBody Map<String,Object> x){
    String email = str(x,"email");
    email = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    String password = req(x,"password");
    if (email.isBlank() || email.length() > 160 || !email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"A valid student email is required because password setup and recovery use email OTP.");
    }
    validatePassword(password, "Student password");
    try { db.update("INSERT INTO students(roll_no,full_name,email,password_hash,course,branch_name,semester,section_name,status) VALUES(?,?,?,?,?,?,?,?,'ACTIVE')", req(x,"rollNo"),req(x,"fullName"),email,passwordEncoder.encode(password),req(x,"course"),strDefault(x,"branch","General"),Integer.parseInt(strDefault(x,"semester","1")),req(x,"section")); audit("STUDENT_CREATED","admin",str(x,"rollNo")); return ResponseEntity.status(201).body(Map.of("message","Student added successfully with login access.")); }
    catch(DuplicateKeyException e){ throw new ResponseStatusException(HttpStatus.CONFLICT,"That roll number or email already exists"); }
    catch(Exception e){ throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Please check required student fields"); }
  }
  @PatchMapping("/students/{id}/password")
  Map<String,Object> setStudentPassword(@PathVariable long id, @RequestBody Map<String,Object> body) {
    String password=req(body,"password");
    validatePassword(password, "Student password");
    int changed=db.update("UPDATE students SET password_hash=? WHERE id=?",passwordEncoder.encode(password),id);
    if(changed==0) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Student not found");
    audit("STUDENT_PASSWORD_CHANGED","admin","studentId="+id);
    return Map.of("message","Student password set successfully");
  }
  @GetMapping("/classrooms") List<Map<String,Object>> classrooms(){ return db.queryForList("SELECT * FROM classrooms ORDER BY building,room_code"); }
  @PostMapping("/classrooms") ResponseEntity<?> addRoom(@RequestBody Map<String,Object> x){
    try { db.update("INSERT INTO classrooms(room_code,building,floor_name,latitude,longitude,radius_m,active) VALUES(?,?,?,?,?,?,TRUE)",req(x,"roomCode"),req(x,"building"),str(x,"floor"),num(x,"latitude"),num(x,"longitude"),radius(x)); audit("CLASSROOM_CREATED","admin",str(x,"roomCode")); return ResponseEntity.status(201).body(Map.of("message","Classroom added")); }
    catch(DuplicateKeyException e){ throw new ResponseStatusException(HttpStatus.CONFLICT,"Room code already exists"); }
    catch(Exception e){ throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Please check classroom fields"); }
  }
  @GetMapping("/timetable") List<Map<String,Object>> timetable(jakarta.servlet.http.HttpServletRequest request){
    return db.queryForList("SELECT t.*,c.room_code classroom FROM timetable t JOIN classrooms c ON c.id=t.classroom_id ORDER BY t.\"DAY_OF_WEEK\",t.start_time,t.course,t.section_name");
  }
  @PostMapping("/timetable") ResponseEntity<?> addTimetable(@RequestBody Map<String,Object> x){
    try { db.update("INSERT INTO timetable(course,branch_name,semester,section_name,subject_name,teacher_name,classroom_id,day_of_week,start_time,end_time) VALUES(?,?,?,?,?,?,?,?,?,?)",req(x,"course"),strDefault(x,"branch","General"),Integer.parseInt(strDefault(x,"semester","1")),req(x,"section"),req(x,"subject"),req(x,"teacher"),Long.parseLong(req(x,"classroomId")),req(x,"day"),req(x,"startTime"),req(x,"endTime")); audit("TIMETABLE_CREATED","admin",str(x,"course")+" "+str(x,"section")); return ResponseEntity.status(201).body(Map.of("message","Timetable entry added")); }
    catch(Exception e){ throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Could not save timetable. Verify classroom and fields."); }
  }
  @PatchMapping("/timetable/{id}") Map<String,Object> updateTimetable(@PathVariable long id,@RequestBody Map<String,Object> x){
    try {
      int changed=db.update("UPDATE timetable SET course=?,branch_name=?,semester=?,section_name=?,subject_name=?,teacher_name=?,classroom_id=?,day_of_week=?,start_time=?,end_time=? WHERE id=?",
          req(x,"course"),strDefault(x,"branch","General"),Integer.parseInt(strDefault(x,"semester","1")),req(x,"section"),req(x,"subject"),req(x,"teacher"),Long.parseLong(req(x,"classroomId")),req(x,"day"),req(x,"startTime"),req(x,"endTime"),id);
      if(changed==0) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Timetable entry not found");
      audit("TIMETABLE_UPDATED","admin","timetable="+id);
      return Map.of("message","Timetable entry updated");
    } catch(ResponseStatusException e){throw e;} catch(Exception e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Could not update timetable. Verify all fields.");}
  }
  @DeleteMapping("/timetable/{id}") Map<String,Object> deleteTimetable(@PathVariable long id){
    if(count("SELECT COUNT(*) FROM timetable WHERE id="+id)==0) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Timetable entry not found");
    long assignments=count("SELECT COUNT(*) FROM teacher_timetable_assignments WHERE timetable_id="+id);
    long sessions=count("SELECT COUNT(*) FROM attendance_sessions WHERE timetable_id="+id);
    long marks=count("SELECT COUNT(*) FROM student_marks WHERE timetable_id="+id);
    if(assignments+sessions+marks>0) throw new ResponseStatusException(HttpStatus.CONFLICT,"This timetable has assignments, sessions, or marks. Remove those records first.");
    db.update("DELETE FROM timetable WHERE id=?",id);
    audit("TIMETABLE_DELETED","admin","timetable="+id);
    return Map.of("message","Timetable entry deleted");
  }
  @PostMapping("/sessions/start") ResponseEntity<?> startSession(@RequestBody Map<String,Object> x, jakarta.servlet.http.HttpServletRequest request){
    try {
      long tid=Long.parseLong(req(x,"timetableId"));
      Map<String,Object> t=db.queryForMap("SELECT * FROM timetable WHERE id=?",tid);
      long room=Long.parseLong(strDefault(x,"classroomId",String.valueOf(t.get("CLASSROOM_ID"))));
      String token=UUID.randomUUID().toString().replace("-","").substring(0,12).toUpperCase(Locale.ROOT);
      LocalDate date=LocalDate.now(); LocalTime start=LocalTime.parse(strDefault(x,"startTime",String.valueOf(t.get("START_TIME")))); LocalTime end=LocalTime.parse(strDefault(x,"endTime",String.valueOf(t.get("END_TIME"))));
      db.update(connection -> {
        PreparedStatement ps = connection.prepareStatement("INSERT INTO attendance_sessions(timetable_id,classroom_id,session_date,start_time,end_time,token,token_expires_at,status) VALUES(?,?,?,?,?,?,?, 'OPEN')");
        ps.setLong(1,tid); ps.setLong(2,room); ps.setDate(3,java.sql.Date.valueOf(date)); ps.setString(4,start.toString()); ps.setString(5,end.toString()); ps.setString(6,token); ps.setTimestamp(7,Timestamp.valueOf(LocalDateTime.now().plusMinutes(10))); return ps;
      });
      Long id=db.queryForObject("SELECT id FROM attendance_sessions WHERE token=?",Long.class,token);
      if(id==null) throw new IllegalStateException("Database did not return the new session id");
      audit("SESSION_STARTED","teacher", "session="+id);
      return ResponseEntity.status(201).body(Map.of("id",id,"token",token,"expiresAt",LocalDateTime.now().plusMinutes(10).toString(),"message","Session started. Share the short-lived token/QR with the class."));
    } catch(ResponseStatusException e){ throw e; }
      catch(Exception e){ throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Could not start session. Check timetable entry and classroom."); }
  }
  @GetMapping("/sessions") List<Map<String,Object>> sessions(jakarta.servlet.http.HttpServletRequest request){
    String sql="SELECT s.id,s.session_date sessionDate,s.start_time startTime,s.end_time endTime,s.status,s.token_expires_at tokenExpiresAt,t.course,t.branch_name branch,t.section_name section,t.subject_name subject,c.room_code room, (SELECT COUNT(*) FROM attendance_records a WHERE a.session_id=s.id AND a.status='PRESENT') presentCount FROM attendance_sessions s JOIN timetable t ON t.id=s.timetable_id JOIN classrooms c ON c.id=s.classroom_id";
    return db.queryForList(sql+" ORDER BY s.id DESC LIMIT 50");
  }

  // Student-only open sessions are scoped to the authenticated student's course/branch/semester/section.
  @GetMapping("/student/sessions")
  List<Map<String,Object>> studentSessions(jakarta.servlet.http.HttpServletRequest request) {
    long studentId=((Number)request.getAttribute("authenticatedStudentId")).longValue();
    return db.queryForList("SELECT s.id,s.session_date sessionDate,s.start_time startTime,s.end_time endTime,s.status, t.course,t.branch_name branch,t.semester,t.section_name section,t.subject_name subject,c.room_code room " +
      "FROM attendance_sessions s JOIN timetable t ON t.id=s.timetable_id JOIN classrooms c ON c.id=s.classroom_id JOIN students st ON st.id=? " +
      "WHERE s.status='OPEN' AND st.status='ACTIVE' AND t.course=st.course AND t.branch_name=st.branch_name AND t.semester=st.semester AND t.section_name=st.section_name ORDER BY s.session_date DESC,s.start_time", studentId);
  }

  @GetMapping("/student/timetable")
  List<Map<String,Object>> studentTimetable(jakarta.servlet.http.HttpServletRequest request) {
    long studentId=((Number)request.getAttribute("authenticatedStudentId")).longValue();
    return timetableForStudent(studentId);
  }

  @GetMapping("/students/{id}/timetable")
  List<Map<String,Object>> studentTimetableById(@PathVariable long id) {
    return timetableForStudent(id);
  }

  private List<Map<String,Object>> timetableForStudent(long studentId) {
    List<Map<String,Object>> rows = db.queryForList("SELECT t.id,t.course,t.branch_name branch,t.semester,t.section_name section,t.subject_name subject,t.teacher_name teacher,c.room_code room,t.day_of_week dayOfWeek,t.start_time startTime,t.end_time endTime " +
      "FROM timetable t JOIN classrooms c ON c.id=t.classroom_id JOIN students st ON st.id=? " +
      "WHERE st.status='ACTIVE' AND UPPER(TRIM(t.course))=UPPER(TRIM(st.course)) AND UPPER(TRIM(t.branch_name))=UPPER(TRIM(st.branch_name)) AND t.semester=st.semester AND UPPER(TRIM(t.section_name))=UPPER(TRIM(st.section_name)) " +
      "ORDER BY t.id", studentId);
    Map<String,Integer> dayOrder = Map.of("MONDAY",1,"TUESDAY",2,"WEDNESDAY",3,"THURSDAY",4,"FRIDAY",5,"SATURDAY",6,"SUNDAY",7);
    rows.sort((left, right) -> {
      int dayCompare = Integer.compare(
        dayOrder.getOrDefault(String.valueOf(left.get("DAYOFWEEK")).trim().toUpperCase(Locale.ROOT), 8),
        dayOrder.getOrDefault(String.valueOf(right.get("DAYOFWEEK")).trim().toUpperCase(Locale.ROOT), 8));
      if (dayCompare != 0) return dayCompare;
      int timeCompare = String.valueOf(left.get("STARTTIME")).compareTo(String.valueOf(right.get("STARTTIME")));
      if (timeCompare != 0) return timeCompare;
      return String.valueOf(left.get("SUBJECT")).compareTo(String.valueOf(right.get("SUBJECT")));
    });
    return rows;
  }

  @PostMapping("/attendance/mark") ResponseEntity<?> mark(@RequestBody Map<String,Object> x){
    throw new ResponseStatusException(HttpStatus.GONE,"Legacy attendance marking is disabled. Use /api/face/attendance so the enrolled face template is checked.");
  }
  @PostMapping("/sessions/{id}/close") Map<String,Object> close(@PathVariable long id, jakarta.servlet.http.HttpServletRequest request){
    int n=db.update("UPDATE attendance_sessions SET status='CLOSED' WHERE id=?",id); if(n==0) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Session not found"); audit("SESSION_CLOSED","teacher","session="+id); return Map.of("message","Session closed"); }
  @PostMapping("/sessions/{id}/delete") Map<String,Object> deleteSession(@PathVariable long id, jakarta.servlet.http.HttpServletRequest request){
    if(count("SELECT COUNT(*) FROM attendance_sessions WHERE id="+id)==0) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Session not found");
    db.update("DELETE FROM attendance_records WHERE session_id=?",id);
    db.update("DELETE FROM attendance_sessions WHERE id=?",id);
    audit("SESSION_DELETED",String.valueOf(request.getAttribute("staffEmail")),"session="+id);
    return Map.of("message","Session deleted");
  }
  @GetMapping("/attendance") List<Map<String,Object>> attendance(jakarta.servlet.http.HttpServletRequest request){
    String sql="SELECT ar.id,st.roll_no rollNo,st.full_name studentName,st.course,st.branch_name branch,st.section_name section,t.subject_name subject,c.room_code room,ar.status,ar.verification_mode verificationMode,ar.marked_at markedAt FROM attendance_records ar JOIN students st ON st.id=ar.student_id JOIN attendance_sessions s ON s.id=ar.session_id JOIN timetable t ON t.id=s.timetable_id JOIN classrooms c ON c.id=s.classroom_id";
    return db.queryForList(sql+" ORDER BY ar.marked_at DESC");
  }
  // Database-backed reporting: totals are computed from persisted sessions and attendance records.
  @GetMapping("/reports/attendance-summary")
  List<Map<String,Object>> attendanceSummary(@RequestParam(required=false) String course,
                                             @RequestParam(required=false) String branch,
                                             @RequestParam(required=false) Integer semester,
                                             @RequestParam(required=false) String section) {
    StringBuilder sql = new StringBuilder("SELECT st.id studentId,st.roll_no rollNo,st.full_name studentName,st.course,st.branch_name branch,st.semester,st.section_name section,")
      .append("COUNT(DISTINCT CASE WHEN ar.status IN ('PRESENT','ABSENT') THEN s.id END) eligibleSessions,")
      .append("COUNT(DISTINCT CASE WHEN ar.status='PRESENT' THEN ar.session_id END) presentSessions,")
      .append("CASE WHEN COUNT(DISTINCT CASE WHEN ar.status IN ('PRESENT','ABSENT') THEN s.id END)=0 THEN 0 ELSE ROUND(100.0*COUNT(DISTINCT CASE WHEN ar.status='PRESENT' THEN s.id END)/COUNT(DISTINCT CASE WHEN ar.status IN ('PRESENT','ABSENT') THEN s.id END),2) END attendancePercentage ")
      .append("FROM students st LEFT JOIN timetable t ON UPPER(TRIM(t.course))=UPPER(TRIM(st.course)) AND UPPER(TRIM(t.branch_name))=UPPER(TRIM(st.branch_name)) AND t.semester=st.semester AND UPPER(TRIM(t.section_name))=UPPER(TRIM(st.section_name)) ")
      .append("LEFT JOIN attendance_sessions s ON s.timetable_id=t.id LEFT JOIN attendance_records ar ON ar.student_id=st.id AND ar.session_id=s.id WHERE st.status='ACTIVE'");
    List<Object> args = new ArrayList<>();
    if(course!=null&&!course.isBlank()){sql.append(" AND st.course=?");args.add(course.trim());}
    if(branch!=null&&!branch.isBlank()){sql.append(" AND st.branch_name=?");args.add(branch.trim());}
    if(semester!=null){sql.append(" AND st.semester=?");args.add(semester);}
    if(section!=null&&!section.isBlank()){sql.append(" AND st.section_name=?");args.add(section.trim());}
    sql.append(" GROUP BY st.id,st.roll_no,st.full_name,st.course,st.branch_name,st.semester,st.section_name ORDER BY st.course,st.branch_name,st.semester,st.section_name,st.full_name");
    return db.queryForList(sql.toString(),args.toArray());
  }

  @GetMapping("/students/{id}/attendance-summary")
  Map<String,Object> studentAttendanceSummary(@PathVariable long id) {
    String sql = "SELECT st.id studentId,st.roll_no rollNo,st.full_name studentName,st.course,st.branch_name branch,st.semester,st.section_name section,"
      + "COUNT(DISTINCT CASE WHEN ar.status IN ('PRESENT','ABSENT') THEN s.id END) eligibleSessions,"
      + "COUNT(DISTINCT CASE WHEN ar.status='PRESENT' THEN ar.session_id END) presentSessions,"
      + "CASE WHEN COUNT(DISTINCT CASE WHEN ar.status IN ('PRESENT','ABSENT') THEN s.id END)=0 THEN 0 ELSE ROUND(100.0*COUNT(DISTINCT CASE WHEN ar.status='PRESENT' THEN s.id END)/COUNT(DISTINCT CASE WHEN ar.status IN ('PRESENT','ABSENT') THEN s.id END),2) END attendancePercentage "
      + "FROM students st LEFT JOIN timetable t ON UPPER(TRIM(t.course))=UPPER(TRIM(st.course)) AND UPPER(TRIM(t.branch_name))=UPPER(TRIM(st.branch_name)) AND t.semester=st.semester AND UPPER(TRIM(t.section_name))=UPPER(TRIM(st.section_name)) "
      + "LEFT JOIN attendance_sessions s ON s.timetable_id=t.id LEFT JOIN attendance_records ar ON ar.student_id=st.id AND ar.session_id=s.id "
      + "WHERE st.id=? AND st.status='ACTIVE' GROUP BY st.id,st.roll_no,st.full_name,st.course,st.branch_name,st.semester,st.section_name";
    List<Map<String,Object>> rows = db.queryForList(sql, id);
    if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Student not found or inactive");
    return rows.get(0);
  }

  @GetMapping("/students/{id}/attendance")
  List<Map<String,Object>> studentAttendance(@PathVariable long id) {
    Integer exists=db.queryForObject("SELECT COUNT(*) FROM students WHERE id=?",Integer.class,id);
    if(exists==null||exists==0) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Student not found");
    return db.queryForList("SELECT ar.id,t.id timetableId,s.session_date sessionDate,t.day_of_week dayOfWeek,t.subject_name subject,c.room_code room,COALESCE(ar.status,'NOT MARKED') status,ar.verification_mode verificationMode,ar.marked_at markedAt FROM timetable t JOIN classrooms c ON c.id=t.classroom_id JOIN students st ON st.id=? AND st.status='ACTIVE' AND UPPER(TRIM(t.course))=UPPER(TRIM(st.course)) AND UPPER(TRIM(t.branch_name))=UPPER(TRIM(st.branch_name)) AND t.semester=st.semester AND UPPER(TRIM(t.section_name))=UPPER(TRIM(st.section_name)) LEFT JOIN attendance_sessions s ON s.timetable_id=t.id LEFT JOIN attendance_records ar ON ar.session_id=s.id AND ar.student_id=st.id ORDER BY s.session_date DESC NULLS LAST,t.subject_name,ar.marked_at DESC",id);
  }

  // Administration: account status controls and teacher directory.
  @PatchMapping("/students/{id}/status")
  Map<String,Object> setStudentStatus(@PathVariable long id, @RequestBody Map<String,Object> body) {
    String status = req(body,"status").toUpperCase(Locale.ROOT);
    if (!Set.of("ACTIVE","INACTIVE").contains(status)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Status must be ACTIVE or INACTIVE");
    int changed = db.update("UPDATE students SET status=? WHERE id=?",status,id);
    if (changed==0) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Student not found");
    audit("STUDENT_STATUS_CHANGED","admin","studentId="+id+", status="+status);
    return Map.of("message","Student account status updated","id",id,"status",status);
  }

  @DeleteMapping("/students/{id}")
  Map<String,Object> deleteStudent(@PathVariable long id) {
    Map<String,Object> student;
    try { student=db.queryForMap("SELECT id,email,roll_no FROM students WHERE id=?",id); }
    catch(Exception e) { throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Student not found"); }
    db.update("DELETE FROM attendance_records WHERE student_id=?",id);
    db.update("DELETE FROM face_templates WHERE student_id=?",id);
    db.update("DELETE FROM attendance_notifications WHERE student_id=?",id);
    db.update("DELETE FROM student_marks WHERE student_id=?",id);
    db.update("DELETE FROM password_reset_tokens WHERE LOWER(email)=LOWER(?)",student.get("EMAIL"));
    db.update("DELETE FROM password_reset_otps WHERE LOWER(email)=LOWER(?)",student.get("EMAIL"));
    db.update("DELETE FROM students WHERE id=?",id);
    audit("STUDENT_DELETED","admin","studentId="+id+", rollNo="+student.get("ROLL_NO"));
    return Map.of("message","Student deleted permanently","id",id);
  }

  @GetMapping("/teachers")
  List<Map<String,Object>> teachers() { return db.queryForList("SELECT t.id,t.full_name fullName,t.email,t.department,t.status,t.created_at createdAt,su.id staffUserId FROM teachers t JOIN staff_users su ON LOWER(su.email)=LOWER(t.email) AND su.role='TEACHER' ORDER BY t.full_name"); }

  @PostMapping("/teachers")
  ResponseEntity<?> addTeacher(@RequestBody Map<String,Object> body) {
    String email=req(body,"email").toLowerCase(Locale.ROOT), password=req(body,"password");
    if (!email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Enter a valid teacher email");
    if (!password.matches("^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[^A-Za-z0-9]).{12,72}$")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Initial password must be 12-72 characters and include upper/lowercase, a number and a symbol");
    try {
      String fullName=req(body,"fullName"), department=req(body,"department");
      db.update("INSERT INTO teachers(full_name,email,department,status,password_hash) VALUES(?,?,?,'ACTIVE',?)",fullName,email,department,passwordEncoder.encode(password));
      db.update("INSERT INTO staff_users(full_name,email,password_hash,role,department,status) VALUES(?,?,?,'TEACHER',?,'ACTIVE')",fullName,email,passwordEncoder.encode(password),department);
      audit("TEACHER_CREATED","admin",email);
      return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("message","Teacher added with login access. Share the initial password securely.","email",email));
    } catch (DuplicateKeyException ex) { throw new ResponseStatusException(HttpStatus.CONFLICT,"A teacher with this email already exists"); }
  }

  @PatchMapping("/teachers/{id}/status")
  Map<String,Object> setTeacherStatus(@PathVariable long id,@RequestBody Map<String,Object> body) {
    String status=req(body,"status").toUpperCase(Locale.ROOT);
    if (!Set.of("ACTIVE","INACTIVE").contains(status)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Status must be ACTIVE or INACTIVE");
    int changed=db.update("UPDATE teachers SET status=? WHERE id=?",status,id);
    if(changed==0) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Teacher not found");
    db.update("UPDATE staff_users SET status=? WHERE LOWER(email)=(SELECT LOWER(email) FROM teachers WHERE id=?) AND role='TEACHER'",status,id);
    if("INACTIVE".equals(status)) db.update("UPDATE staff_sessions SET revoked=TRUE WHERE user_id IN (SELECT id FROM staff_users WHERE LOWER(email)=(SELECT LOWER(email) FROM teachers WHERE id=?))",id);
    audit("TEACHER_STATUS_CHANGED","admin","teacherId="+id+", status="+status);
    return Map.of("message","Teacher status updated","id",id,"status",status);
  }

  @DeleteMapping("/teachers/{id}")
  Map<String,Object> deleteTeacher(@PathVariable long id) {
    Map<String,Object> teacher;
    try { teacher=db.queryForMap("SELECT id,email,full_name FROM teachers WHERE id=?",id); }
    catch(Exception e) { throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Teacher not found"); }
    String email=String.valueOf(teacher.get("EMAIL"));
    db.update("DELETE FROM staff_sessions WHERE user_id IN (SELECT id FROM staff_users WHERE LOWER(email)=LOWER(?) AND role='TEACHER')",email);
    db.update("DELETE FROM teacher_timetable_assignments WHERE teacher_user_id IN (SELECT id FROM staff_users WHERE LOWER(email)=LOWER(?) AND role='TEACHER')",email);
    db.update("DELETE FROM student_marks WHERE entered_by IN (SELECT id FROM staff_users WHERE LOWER(email)=LOWER(?) AND role='TEACHER')",email);
    db.update("DELETE FROM staff_users WHERE LOWER(email)=LOWER(?) AND role='TEACHER'",email);
    db.update("DELETE FROM teachers WHERE id=?",id);
    audit("TEACHER_DELETED","admin","teacherId="+id+", email="+email);
    return Map.of("message","Teacher deleted permanently","id",id);
  }

  @GetMapping("/assignments") List<Map<String,Object>> assignments(){
    return db.queryForList("SELECT a.id,a.teacher_user_id teacherUserId,su.full_name teacherName,su.email teacherEmail,a.timetable_id timetableId,t.course,t.branch_name branch,t.semester,t.section_name section,t.subject_name subject,t.\"DAY_OF_WEEK\" dayOfWeek,t.start_time startTime,t.end_time endTime,c.room_code room,a.created_at createdAt FROM teacher_timetable_assignments a JOIN staff_users su ON su.id=a.teacher_user_id JOIN timetable t ON t.id=a.timetable_id JOIN classrooms c ON c.id=t.classroom_id ORDER BY su.full_name,t.\"DAY_OF_WEEK\",t.start_time");
  }
  @PostMapping("/assignments") ResponseEntity<?> assignTeacher(@RequestBody Map<String,Object> body, jakarta.servlet.http.HttpServletRequest request){
    try {
      long teacherUserId=Long.parseLong(req(body,"teacherUserId")), timetableId=Long.parseLong(req(body,"timetableId"));
      Map<String,Object> teacher=db.queryForMap("SELECT id,full_name,email FROM staff_users WHERE id=? AND role='TEACHER' AND status='ACTIVE'",teacherUserId);
      Map<String,Object> entry=db.queryForMap("SELECT id,subject_name FROM timetable WHERE id=?",timetableId);
      db.update("INSERT INTO teacher_timetable_assignments(teacher_user_id,timetable_id,assigned_by) VALUES(?,?,?)",teacherUserId,timetableId,String.valueOf(request.getAttribute("staffEmail")));
      audit("TEACHER_CLASS_ASSIGNED",String.valueOf(request.getAttribute("staffEmail")),"teacher="+teacher.get("EMAIL")+", timetableId="+timetableId+", subject="+entry.get("SUBJECT_NAME"));
      return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("message","Class assigned to teacher successfully"));
    } catch(DuplicateKeyException ex){ throw new ResponseStatusException(HttpStatus.CONFLICT,"This class is already assigned to that teacher"); }
      catch(ResponseStatusException ex){ throw ex; }
      catch(Exception ex){ throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Select a valid active teacher and timetable entry"); }
  }
  @DeleteMapping("/assignments/{id}") Map<String,Object> removeAssignment(@PathVariable long id, jakarta.servlet.http.HttpServletRequest request){
    int n=db.update("DELETE FROM teacher_timetable_assignments WHERE id=?",id);
    if(n==0) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Assignment not found");
    audit("TEACHER_CLASS_UNASSIGNED",String.valueOf(request.getAttribute("staffEmail")),"assignmentId="+id);
    return Map.of("message","Class assignment removed");
  }

  @GetMapping("/audit") List<Map<String,Object>> auditLogs(){ return db.queryForList("SELECT * FROM audit_logs ORDER BY created_at DESC LIMIT 100"); }
  @DeleteMapping("/audit/{id}") Map<String,Object> deleteAudit(@PathVariable long id){
    int changed=db.update("DELETE FROM audit_logs WHERE id=?",id);
    if(changed==0) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Audit event not found");
    return Map.of("message","Audit event deleted");
  }
  private void audit(String a,String actor,String detail){ db.update("INSERT INTO audit_logs(action_name,actor,detail) VALUES(?,?,?)",a,actor,detail); }
  private String req(Map<String,Object> x,String k){ String s=str(x,k); if(s==null||s.isBlank()) throw new IllegalArgumentException(k+" required"); return s.trim(); }
  private void validatePassword(String password,String label) {
    if(!password.matches("^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[^A-Za-z0-9]).{12,72}$"))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,label+" must be 12-72 characters and include upper/lowercase, a number and a symbol");
  }
  private String str(Map<String,Object>x,String k){ Object v=x.get(k); return v==null?null:String.valueOf(v); }
  private String strDefault(Map<String,Object>x,String k,String d){ String s=str(x,k); return s==null||s.isBlank()?d:s.trim(); }
  private int radius(Map<String,Object> x){ int value=Integer.parseInt(strDefault(x,"radiusM","50")); if(value<1) throw new IllegalArgumentException("radiusM must be at least 1 metre"); return value; }
  private Double num(Map<String,Object>x,String k){ String s=str(x,k); return s==null||s.isBlank()?null:Double.valueOf(s); }

  @Bean CommandLineRunner seedData(JdbcTemplate db){ return args -> {
    if(count("SELECT COUNT(*) FROM classrooms")==0){
      db.update("INSERT INTO classrooms(room_code,building,floor_name,latitude,longitude,radius_m,active) VALUES('A-101','Academic Block A','Ground',26.4499,80.3319,40,TRUE),('A-102','Academic Block A','Ground',26.4500,80.3320,40,TRUE),('B-201','Academic Block B','First',26.4510,80.3330,50,TRUE),('C-301','Academic Block C','Second',26.4520,80.3340,50,TRUE)");
    }
    if(count("SELECT COUNT(*) FROM students")==0){
      db.update("INSERT INTO students(roll_no,full_name,email,course,branch_name,semester,section_name,status) VALUES('KIT23CSE001','Aarav Sharma','aarav@example.edu','B.Tech','CSE',7,'A','ACTIVE'),('KIT23CSE002','Ananya Singh','ananya@example.edu','B.Tech','CSE',7,'A','ACTIVE'),('KIT23CSE003','Vivaan Verma','vivaan@example.edu','B.Tech','CSE',7,'B','ACTIVE'),('KIT23MCA001','Ishita Gupta','ishita@example.edu','MCA','MCA',3,'A','ACTIVE'),('KIT23BCA001','Kabir Mishra','kabir@example.edu','BCA','BCA',5,'A','ACTIVE'),('KIT23BBA001','Meera Patel','meera@example.edu','BBA','BBA',5,'A','ACTIVE'),('KIT23PH001','Aditya Yadav','aditya@example.edu','Pharmacy','Pharmacy',3,'A','ACTIVE')");
    }
    if(count("SELECT COUNT(*) FROM timetable")==0){
      db.update("INSERT INTO timetable(course,branch_name,semester,section_name,subject_name,teacher_name,classroom_id,day_of_week,start_time,end_time) VALUES('B.Tech','CSE',7,'A','Java & Spring Boot','Dr. Neha Tayal',1,'MONDAY','09:00','10:00'),('B.Tech','CSE',7,'B','Database Systems','Prof. Amit Singh',2,'MONDAY','09:00','10:00'),('B.Tech','CSE',7,'A','Software Engineering','Dr. R. Verma',3,'MONDAY','10:00','11:00'),('MCA','MCA',3,'A','Cloud Computing','Prof. S. Khan',3,'MONDAY','09:00','10:00'),('BCA','BCA',5,'A','Web Development','Prof. P. Das',4,'MONDAY','09:00','10:00'),('BBA','BBA',5,'A','Business Analytics','Prof. K. Jain',4,'MONDAY','10:00','11:00'),('Pharmacy','Pharmacy',3,'A','Pharmaceutics','Dr. M. Rao',3,'MONDAY','09:00','10:00')");
    }
  }; }
}

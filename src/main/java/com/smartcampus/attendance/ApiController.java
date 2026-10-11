package com.smartcampus.attendance;

import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.DuplicateKeyException;
import java.sql.*;
import java.time.*;
import java.util.*;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
class ApiController {
  private final JdbcTemplate db;
  ApiController(JdbcTemplate db) { this.db = db; }

  @GetMapping("/health") Map<String,Object> health(){ return Map.of("status","UP","app","Smart Campus Attendance ERP","time",Instant.now().toString()); }
  @GetMapping("/dashboard") Map<String,Object> dashboard(){
    return Map.of("students",count("SELECT COUNT(*) FROM students WHERE status='ACTIVE'"),"classrooms",count("SELECT COUNT(*) FROM classrooms WHERE active=TRUE"),"sessionsToday",count("SELECT COUNT(*) FROM attendance_sessions WHERE session_date=CURRENT_DATE"),"presentToday",count("SELECT COUNT(*) FROM attendance_records ar JOIN attendance_sessions s ON s.id=ar.session_id WHERE s.session_date=CURRENT_DATE AND ar.status='PRESENT'"),"recent",db.queryForList("SELECT ar.id, st.full_name studentName, st.roll_no rollNo, t.subject_name subject, c.room_code room, ar.status, ar.marked_at markedAt FROM attendance_records ar JOIN students st ON st.id=ar.student_id JOIN attendance_sessions s ON s.id=ar.session_id JOIN timetable t ON t.id=s.timetable_id JOIN classrooms c ON c.id=s.classroom_id ORDER BY ar.marked_at DESC LIMIT 8"));
  }
  private long count(String q){ Long n=db.queryForObject(q,Long.class); return n==null?0:n; }

  @GetMapping("/students") List<Map<String,Object>> students(){ return db.queryForList("SELECT * FROM students ORDER BY course,branch_name,section_name,full_name"); }
  @PostMapping("/students") ResponseEntity<?> addStudent(@RequestBody Map<String,Object> x){
    try { db.update("INSERT INTO students(roll_no,full_name,email,course,branch_name,semester,section_name,status) VALUES(?,?,?,?,?,?,?,'ACTIVE')", req(x,"rollNo"),req(x,"fullName"),str(x,"email"),req(x,"course"),strDefault(x,"branch","General"),Integer.parseInt(strDefault(x,"semester","1")),req(x,"section")); audit("STUDENT_CREATED","admin",str(x,"rollNo")); return ResponseEntity.status(201).body(Map.of("message","Student added successfully")); }
    catch(DuplicateKeyException e){ throw new ResponseStatusException(HttpStatus.CONFLICT,"Roll number already exists"); }
    catch(Exception e){ throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Please check required student fields"); }
  }
  @GetMapping("/classrooms") List<Map<String,Object>> classrooms(){ return db.queryForList("SELECT * FROM classrooms ORDER BY building,room_code"); }
  @PostMapping("/classrooms") ResponseEntity<?> addRoom(@RequestBody Map<String,Object> x){
    try { db.update("INSERT INTO classrooms(room_code,building,floor_name,latitude,longitude,radius_m,active) VALUES(?,?,?,?,?,?,TRUE)",req(x,"roomCode"),req(x,"building"),str(x,"floor"),num(x,"latitude"),num(x,"longitude"),Integer.parseInt(strDefault(x,"radiusM","50"))); audit("CLASSROOM_CREATED","admin",str(x,"roomCode")); return ResponseEntity.status(201).body(Map.of("message","Classroom added")); }
    catch(DuplicateKeyException e){ throw new ResponseStatusException(HttpStatus.CONFLICT,"Room code already exists"); }
    catch(Exception e){ throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Please check classroom fields"); }
  }
  @GetMapping("/timetable") List<Map<String,Object>> timetable(){ return db.queryForList("SELECT t.*,c.room_code classroom FROM timetable t JOIN classrooms c ON c.id=t.classroom_id ORDER BY t.day_of_week,t.start_time,t.course,t.section_name"); }
  @PostMapping("/timetable") ResponseEntity<?> addTimetable(@RequestBody Map<String,Object> x){
    try { db.update("INSERT INTO timetable(course,branch_name,semester,section_name,subject_name,teacher_name,classroom_id,day_of_week,start_time,end_time) VALUES(?,?,?,?,?,?,?,?,?,?)",req(x,"course"),strDefault(x,"branch","General"),Integer.parseInt(strDefault(x,"semester","1")),req(x,"section"),req(x,"subject"),req(x,"teacher"),Long.parseLong(req(x,"classroomId")),req(x,"day"),req(x,"startTime"),req(x,"endTime")); audit("TIMETABLE_CREATED","admin",str(x,"course")+" "+str(x,"section")); return ResponseEntity.status(201).body(Map.of("message","Timetable entry added")); }
    catch(Exception e){ throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Could not save timetable. Verify classroom and fields."); }
  }
  @PostMapping("/sessions/start") ResponseEntity<?> startSession(@RequestBody Map<String,Object> x){
    try {
      long tid=Long.parseLong(req(x,"timetableId"));
      Map<String,Object> t=db.queryForMap("SELECT * FROM timetable WHERE id=?",tid);
      long room=Long.parseLong(strDefault(x,"classroomId",String.valueOf(t.get("CLASSROOM_ID"))));
      String token=UUID.randomUUID().toString().replace("-","").substring(0,12).toUpperCase(Locale.ROOT);
      LocalDate date=LocalDate.now(); LocalTime start=LocalTime.parse(strDefault(x,"startTime",String.valueOf(t.get("START_TIME")))); LocalTime end=LocalTime.parse(strDefault(x,"endTime",String.valueOf(t.get("END_TIME"))));
      db.update("INSERT INTO attendance_sessions(timetable_id,classroom_id,session_date,start_time,end_time,token,token_expires_at,status) VALUES(?,?,?,?,?,?,?, 'OPEN')",tid,room,date.toString(),start.toString(),end.toString(),token,Timestamp.valueOf(LocalDateTime.now().plusMinutes(10)));
      long id=db.queryForObject("SELECT MAX(id) FROM attendance_sessions",Long.class); audit("SESSION_STARTED","teacher", "session="+id);
      return ResponseEntity.status(201).body(Map.of("id",id,"token",token,"expiresAt",LocalDateTime.now().plusMinutes(10).toString(),"message","Session started. Share the short-lived token/QR with the class."));
    } catch(Exception e){ throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Could not start session. Check timetable entry and classroom."); }
  }
  @GetMapping("/sessions") List<Map<String,Object>> sessions(){ return db.queryForList("SELECT s.id,s.session_date sessionDate,s.start_time startTime,s.end_time endTime,s.status,s.token_expires_at tokenExpiresAt,t.course,t.branch_name branch,t.section_name section,t.subject_name subject,c.room_code room, (SELECT COUNT(*) FROM attendance_records a WHERE a.session_id=s.id AND a.status='PRESENT') presentCount FROM attendance_sessions s JOIN timetable t ON t.id=s.timetable_id JOIN classrooms c ON c.id=s.classroom_id ORDER BY s.id DESC LIMIT 50"); }
  @PostMapping("/attendance/mark") ResponseEntity<?> mark(@RequestBody Map<String,Object> x){
    try {
      long studentId=Long.parseLong(req(x,"studentId")), sessionId=Long.parseLong(req(x,"sessionId")); String token=req(x,"token");
      Map<String,Object> s=db.queryForMap("SELECT s.*,t.course,t.branch_name,t.semester,t.section_name FROM attendance_sessions s JOIN timetable t ON t.id=s.timetable_id WHERE s.id=?",sessionId);
      if(!"OPEN".equalsIgnoreCase(String.valueOf(s.get("STATUS")))) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Attendance session is closed");
      Timestamp expiry=(Timestamp)s.get("TOKEN_EXPIRES_AT"); if(expiry.toLocalDateTime().isBefore(LocalDateTime.now())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"QR/session token has expired");
      if(!String.valueOf(s.get("TOKEN")).equalsIgnoreCase(token)) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Invalid classroom token");
      Map<String,Object> st=db.queryForMap("SELECT * FROM students WHERE id=? AND status='ACTIVE'",studentId);
      if(!String.valueOf(st.get("COURSE")).equalsIgnoreCase(String.valueOf(s.get("COURSE"))) || !String.valueOf(st.get("BRANCH_NAME")).equalsIgnoreCase(String.valueOf(s.get("BRANCH_NAME"))) || !String.valueOf(st.get("SECTION_NAME")).equalsIgnoreCase(String.valueOf(s.get("SECTION_NAME"))) || ((Number)st.get("SEMESTER")).intValue()!=((Number)s.get("SEMESTER")).intValue()) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Student is not enrolled in this class/section");
      // Important: this starter validates session and enrollment. Production face/liveness verification must be implemented before enabling real attendance.
      String mode=strDefault(x,"verificationMode","DEMO_UI_ONLY");
      if(!"DEMO_UI_ONLY".equals(mode) && !"FACE_VERIFIED".equals(mode)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unsupported verification mode");
      db.update("INSERT INTO attendance_records(student_id,session_id,status,verification_mode,note) VALUES(?,?,'PRESENT',?,?)",studentId,sessionId,mode,"Prototype: face/liveness verification adapter not connected");
      audit("ATTENDANCE_MARKED",String.valueOf(st.get("ROLL_NO")),"session="+sessionId);
      return ResponseEntity.status(201).body(Map.of("message","Attendance marked in prototype","student",st.get("FULL_NAME"),"sessionId",sessionId,"verificationMode",mode,"warning","Prototype only: real face verification and liveness must be connected before production use."));
    } catch(DuplicateKeyException e){ throw new ResponseStatusException(HttpStatus.CONFLICT,"Attendance already marked for this session"); }
      catch(ResponseStatusException e){ throw e; }
      catch(Exception e){ throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Could not mark attendance. Check student, session and token."); }
  }
  @PostMapping("/sessions/{id}/close") Map<String,Object> close(@PathVariable long id){ int n=db.update("UPDATE attendance_sessions SET status='CLOSED' WHERE id=?",id); if(n==0) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Session not found"); audit("SESSION_CLOSED","teacher","session="+id); return Map.of("message","Session closed"); }
  @GetMapping("/attendance") List<Map<String,Object>> attendance(){ return db.queryForList("SELECT ar.id,st.roll_no rollNo,st.full_name studentName,st.course,st.branch_name branch,st.section_name section,t.subject_name subject,c.room_code room,ar.status,ar.verification_mode verificationMode,ar.marked_at markedAt FROM attendance_records ar JOIN students st ON st.id=ar.student_id JOIN attendance_sessions s ON s.id=ar.session_id JOIN timetable t ON t.id=s.timetable_id JOIN classrooms c ON c.id=s.classroom_id ORDER BY ar.marked_at DESC"); }
  @GetMapping("/audit") List<Map<String,Object>> auditLogs(){ return db.queryForList("SELECT * FROM audit_logs ORDER BY created_at DESC LIMIT 100"); }
  private void audit(String a,String actor,String detail){ db.update("INSERT INTO audit_logs(action_name,actor,detail) VALUES(?,?,?)",a,actor,detail); }
  private String req(Map<String,Object> x,String k){ String s=str(x,k); if(s==null||s.isBlank()) throw new IllegalArgumentException(k+" required"); return s.trim(); }
  private String str(Map<String,Object>x,String k){ Object v=x.get(k); return v==null?null:String.valueOf(v); }
  private String strDefault(Map<String,Object>x,String k,String d){ String s=str(x,k); return s==null||s.isBlank()?d:s.trim(); }
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

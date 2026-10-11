package com.smartcampus.attendance;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;

@RestController
@RequestMapping("/api/face")
@CrossOrigin(origins = "*")
class FaceController {
  private static final double MATCH_THRESHOLD = 0.60;
  private final JdbcTemplate db;
  private final ObjectMapper mapper;
  private final FaceAuthService faceAuth;
  FaceController(JdbcTemplate db, ObjectMapper mapper, FaceAuthService faceAuth) { this.db=db; this.mapper=mapper; this.faceAuth=faceAuth; }

  @PostMapping("/logout")
  Map<String,Object> logout(@RequestBody Map<String,Object> body) {
    faceAuth.revokeSession(stringValue(body.get("accessToken")));
    return Map.of("message","Student session signed out");
  }

  @GetMapping("/status/{studentId}")
  Map<String,Object> status(@PathVariable long studentId) {
    requireActiveStudent(studentId);
    Integer n=db.queryForObject("SELECT COUNT(*) FROM face_templates WHERE student_id=?",Integer.class,studentId);
    return Map.of("enrolled",n!=null && n>0,"message",n!=null && n>0 ? "Face profile already enrolled" : "Face profile not enrolled");
  }

  @PostMapping("/enroll")
  ResponseEntity<?> enroll(@RequestBody Map<String,Object> body) {
    long id=longValue(body,"studentId"); requireActiveStudent(id);
    faceAuth.requireSession(stringValue(body.get("accessToken")),id);
    String challenge=stringValue(body.get("faceChallenge")); faceAuth.validateChallenge(challenge,id);
    List<Double> vector=descriptor(body.get("descriptor"));
    Integer n=db.queryForObject("SELECT COUNT(*) FROM face_templates WHERE student_id=?",Integer.class,id);
    if(n!=null && n>0) throw new ResponseStatusException(HttpStatus.CONFLICT,"Face profile already exists. Use face verification or contact an administrator to reset enrollment.");
    try {
      db.update("INSERT INTO face_templates(student_id,descriptor_json) VALUES(?,?)",id,mapper.writeValueAsString(vector));
      audit("FACE_ENROLLED",studentRoll(id),"Face embedding saved; raw image not stored");
      faceAuth.consumeChallenge(challenge);
      return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("message","Face enrolled successfully. Your raw camera image was not saved.","accessToken",faceAuth.issueSession(id)));
    } catch(DuplicateKeyException e) { throw new ResponseStatusException(HttpStatus.CONFLICT,"Face profile already exists"); }
      catch(Exception e) { throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,"Could not save face profile"); }
  }

  @PostMapping("/verify")
  Map<String,Object> verify(@RequestBody Map<String,Object> body) {
    long id=longValue(body,"studentId"); requireActiveStudent(id);
    faceAuth.requireSession(stringValue(body.get("accessToken")),id);
    String challenge=stringValue(body.get("faceChallenge")); faceAuth.validateChallenge(challenge,id);
    double distance=matchDistance(id,descriptor(body.get("descriptor")));
    if(distance>MATCH_THRESHOLD) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Face did not match the enrolled profile. Try again in good lighting.");
    audit("FACE_VERIFIED",studentRoll(id),"Face embedding matched; distance="+String.format(Locale.ROOT,"%.4f",distance));
    faceAuth.consumeChallenge(challenge);
    return Map.of("verified",true,"message","Face matched successfully","distance",distance,"accessToken",faceAuth.issueSession(id));
  }

  @PostMapping("/challenge")
  Map<String,Object> attendanceChallenge(@RequestBody Map<String,Object> body) {
    long studentId=longValue(body,"studentId");
    faceAuth.requireSession(stringValue(body.get("accessToken")),studentId);
    requireActiveStudent(studentId);
    return Map.of("faceChallenge",faceAuth.issueChallenge(studentId),"expiresInSeconds",300);
  }

  @GetMapping("/challenge")
  Map<String,Object> attendanceChallengeFromLegacyPage(HttpServletRequest request) {
    String header=request.getHeader("Authorization");
    String accessToken=header!=null && header.regionMatches(true,0,"Bearer ",0,7) ? header.substring(7).trim() : "";
    long studentId=faceAuth.authenticatedStudentId(accessToken);
    faceAuth.requireSession(accessToken,studentId);
    requireActiveStudent(studentId);
    return Map.of("faceChallenge",faceAuth.issueChallenge(studentId),"expiresInSeconds",300);
  }

  @PostMapping("/attendance")
  ResponseEntity<?> attendance(@RequestBody Map<String,Object> body) {
    long studentId=longValue(body,"studentId"), sessionId=longValue(body,"sessionId");
    String token=stringValue(body.get("token"));
    faceAuth.requireSession(stringValue(body.get("accessToken")),studentId);
    requireActiveStudent(studentId);
    String faceChallenge=stringValue(body.get("faceChallenge"));
    faceAuth.validateChallenge(faceChallenge,studentId);
    double distance=matchDistance(studentId,descriptor(body.get("descriptor")));
    if(distance>MATCH_THRESHOLD) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Face did not match. Attendance was not marked.");
    try {
      Map<String,Object> session=db.queryForMap("SELECT s.*,t.course,t.branch_name,t.semester,t.section_name FROM attendance_sessions s JOIN timetable t ON t.id=s.timetable_id WHERE s.id=?",sessionId);
      if(!"OPEN".equalsIgnoreCase(String.valueOf(session.get("STATUS")))) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Attendance session is closed");
      Timestamp expiry=(Timestamp)session.get("TOKEN_EXPIRES_AT");
      if(expiry.toLocalDateTime().isBefore(LocalDateTime.now())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Session token has expired");
      if(!String.valueOf(session.get("TOKEN")).equalsIgnoreCase(token)) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Invalid classroom token");
      Map<String,Object> student=db.queryForMap("SELECT * FROM students WHERE id=? AND status='ACTIVE'",studentId);
      if(!eq(student.get("COURSE"),session.get("COURSE")) || !eq(student.get("BRANCH_NAME"),session.get("BRANCH_NAME")) || !eq(student.get("SECTION_NAME"),session.get("SECTION_NAME")) || ((Number)student.get("SEMESTER")).intValue()!=((Number)session.get("SEMESTER")).intValue())
        throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Student is not enrolled in this class/section");
      db.update("INSERT INTO attendance_records(student_id,session_id,status,verification_mode,note) VALUES(?,?,'PRESENT','FACE_MATCH','Face embedding matched; liveness not verified')",studentId,sessionId);
      faceAuth.consumeChallenge(faceChallenge);
      audit("ATTENDANCE_MARKED_FACE",String.valueOf(student.get("ROLL_NO")),"session="+sessionId+", distance="+String.format(Locale.ROOT,"%.4f",distance));
      return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("message","Face matched and attendance marked","student",student.get("FULL_NAME"),"sessionId",sessionId,"verificationMode","FACE_MATCH","warning","Prototype matching only; anti-spoof/liveness checks are not implemented."));
    } catch(DuplicateKeyException e) { throw new ResponseStatusException(HttpStatus.CONFLICT,"Attendance already marked for this session"); }
      catch(ResponseStatusException e) { throw e; }
      catch(Exception e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Could not mark attendance. Check session and token."); }
  }

  private double matchDistance(long id,List<Double> probe) {
    List<String> rows=db.query("SELECT descriptor_json FROM face_templates WHERE student_id=?",(rs,n)->rs.getString(1),id);
    if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,"No face profile enrolled. Sign in and complete face enrollment first.");
    try {
      List<Double> saved=mapper.readValue(rows.get(0),new TypeReference<List<Double>>(){});
      if(saved.size()!=probe.size()) throw new IllegalArgumentException();
      double sum=0; for(int i=0;i<saved.size();i++){double d=saved.get(i)-probe.get(i);sum+=d*d;} return Math.sqrt(sum);
    } catch(Exception e) { throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,"Stored face profile is invalid; ask an administrator to reset it"); }
  }
  private List<Double> descriptor(Object raw) {
    if(!(raw instanceof List<?> list) || list.size()!=128) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"A valid 128-value face descriptor is required");
    List<Double> out=new ArrayList<>(128);
    for(Object x:list) { if(!(x instanceof Number)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid face descriptor"); double v=((Number)x).doubleValue(); if(!Double.isFinite(v) || Math.abs(v)>10) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid face descriptor"); out.add(v); }
    return out;
  }
  private void requireActiveStudent(long id) { Integer n=db.queryForObject("SELECT COUNT(*) FROM students WHERE id=? AND status='ACTIVE'",Integer.class,id); if(n==null||n==0) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Active student account not found"); }
  private long longValue(Map<String,Object> b,String key){try{return Long.parseLong(stringValue(b.get(key)));}catch(Exception e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,key+" is required");}}
  private String stringValue(Object o){return o==null?"":String.valueOf(o).trim();}
  private boolean eq(Object a,Object b){return String.valueOf(a).equalsIgnoreCase(String.valueOf(b));}
  private String studentRoll(long id){return db.queryForObject("SELECT roll_no FROM students WHERE id=?",String.class,id);}
  private void audit(String action,String actor,String detail){db.update("INSERT INTO audit_logs(action_name,actor,detail) VALUES(?,?,?)",action,actor,detail);}
}

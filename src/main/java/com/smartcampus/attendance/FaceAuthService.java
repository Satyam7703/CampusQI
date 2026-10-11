package com.smartcampus.attendance;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
class FaceAuthService {
  private record Challenge(long studentId, Instant expiresAt) {}
  private record LoginSession(long studentId, Instant expiresAt, boolean faceVerified) {}
  private final ConcurrentHashMap<String,Challenge> challenges = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String,LoginSession> sessions = new ConcurrentHashMap<>();

  String issueChallenge(long studentId) {
    String token=UUID.randomUUID().toString()+UUID.randomUUID().toString().replace("-","");
    challenges.put(token,new Challenge(studentId,Instant.now().plusSeconds(300)));
    return token;
  }
  void validateChallenge(String token,long studentId) {
    Challenge c=token==null?null:challenges.get(token);
    if(c==null || c.studentId()!=studentId || c.expiresAt().isBefore(Instant.now()))
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Face login challenge expired. Sign in again.");
  }
  void consumeChallenge(String token) { challenges.remove(token); }
  String issuePasswordSession(long studentId) {
    return issueSession(studentId, false);
  }
  String issueSession(long studentId) {
    return issueSession(studentId, true);
  }
  private String issueSession(long studentId, boolean faceVerified) {
    String token=UUID.randomUUID().toString()+UUID.randomUUID().toString().replace("-","");
    sessions.put(token,new LoginSession(studentId,Instant.now().plusSeconds(8*60*60),faceVerified));
    return token;
  }
  long authenticatedStudentId(String token) {
    LoginSession s=token==null?null:sessions.get(token);
    if(s==null || s.expiresAt().isBefore(Instant.now())) {
      if(token!=null) sessions.remove(token);
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Student session expired or invalid. Sign in again.");
    }
    return s.studentId();
  }
  void requireSession(String token,long studentId) {
    if(authenticatedStudentId(token)!=studentId)
      throw new ResponseStatusException(HttpStatus.FORBIDDEN,"This student session cannot access another student's data.");
  }
  void requireVerifiedSession(String token,long studentId) {
    LoginSession session=token==null?null:sessions.get(token);
    if(session==null || session.expiresAt().isBefore(Instant.now())) {
      if(token!=null) sessions.remove(token);
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Student session expired or invalid. Sign in again.");
    }
    if(session.studentId()!=studentId)
      throw new ResponseStatusException(HttpStatus.FORBIDDEN,"This student session cannot access another student's data.");
    if(!session.faceVerified())
      throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,"Face verification is required before marking attendance.");
  }
  void revokeSession(String token) { if(token!=null && !token.isBlank()) sessions.remove(token); }
}

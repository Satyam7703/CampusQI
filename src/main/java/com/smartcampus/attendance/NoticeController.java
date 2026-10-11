package com.smartcampus.attendance;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestController
class NoticeController {
  private final JdbcTemplate db;

  NoticeController(JdbcTemplate db) {
    this.db = db;
  }

  @GetMapping("/api/notices")
  ResponseEntity<List<Map<String, Object>>> list(HttpServletRequest request) {
    boolean isStudent = request.getAttribute("authenticatedStudentId") != null;
    boolean isAdmin = "ADMIN".equals(String.valueOf(request.getAttribute("staffRole")));
    List<Map<String, Object>> notices;
    if (isAdmin) {
      notices = db.queryForList(
          "SELECT id,title,message,audience,created_by createdBy,created_at createdAt " +
          "FROM campus_notices ORDER BY created_at DESC,id DESC LIMIT 20");
    } else {
      String audience = isStudent ? "STUDENT" : "STAFF";
      notices = db.queryForList(
          "SELECT id,title,message,audience,created_by createdBy,created_at createdAt " +
          "FROM campus_notices WHERE audience='ALL' OR audience=? ORDER BY created_at DESC,id DESC LIMIT 20",
          audience);
    }
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(notices);
  }

  @PostMapping("/api/notices")
  Map<String, Object> create(@RequestBody Map<String, Object> body, HttpServletRequest request) {
    requireAdmin(request);
    String title = text(body.get("title"));
    String message = text(body.get("message"));
    String audience = text(body.getOrDefault("audience", "ALL")).toUpperCase(Locale.ROOT);
    if (title.isBlank() || title.length() > 180 || message.isBlank() || message.length() > 2000) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Title and message are required within the allowed length.");
    }
    if (!List.of("ALL", "STUDENT", "STAFF").contains(audience)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Audience must be ALL, STUDENT or STAFF.");
    }
    db.update("INSERT INTO campus_notices(title,message,audience,created_by) VALUES(?,?,?,?)",
        title, message, audience, String.valueOf(request.getAttribute("staffEmail")));
    return Map.of("message", "Notice published.");
  }

  @DeleteMapping("/api/notices/{id}")
  Map<String, Object> delete(@PathVariable long id, HttpServletRequest request) {
    requireAdmin(request);
    int changed = db.update("DELETE FROM campus_notices WHERE id=?", id);
    if (changed == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Notice was not found.");
    return Map.of("message", "Notice deleted.");
  }

  private void requireAdmin(HttpServletRequest request) {
    if (!"ADMIN".equals(String.valueOf(request.getAttribute("staffRole")))) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only an administrator can manage notices.");
    }
  }

  private String text(Object value) {
    return value == null ? "" : String.valueOf(value).trim();
  }
}

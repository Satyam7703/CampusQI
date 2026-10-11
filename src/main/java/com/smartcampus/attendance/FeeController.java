package com.smartcampus.attendance;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api")
class FeeController {
  private final JdbcTemplate db; private final ObjectMapper json = new ObjectMapper();
  @Value("${app.razorpay.key-id:}") private String keyId;
  @Value("${app.razorpay.key-secret:}") private String keySecret;
  FeeController(JdbcTemplate db){this.db=db;}

  @GetMapping("/fees")
  List<Map<String,Object>> fees(){ return db.queryForList("SELECT f.id,f.student_id studentId,s.roll_no rollNo,s.full_name studentName,s.course,s.branch_name branch,s.semester,s.section_name section,f.title,f.amount,f.due_date dueDate,f.status,f.created_at createdAt FROM fee_assignments f JOIN students s ON s.id=f.student_id ORDER BY f.created_at DESC"); }

  @PostMapping("/fees/assign")
  Map<String,Object> assign(@RequestBody Map<String,Object> b, HttpServletRequest r){
    String title=required(b,"title"); double amount=number(b.get("amount")); if(amount<=0) throw bad("Amount must be greater than zero.");
    String due=String.valueOf(b.getOrDefault("dueDate","")).trim(); String actor=String.valueOf(r.getAttribute("staffEmail"));
    String scope=String.valueOf(b.getOrDefault("scope","STUDENT")).toUpperCase(Locale.ROOT); List<Long> ids=new ArrayList<>();
    if("STUDENT".equals(scope)) ids.add(longValue(b.get("studentId")));
    else ids=db.queryForList("SELECT id FROM students WHERE status='ACTIVE' AND UPPER(TRIM(course))=UPPER(TRIM(?)) AND UPPER(TRIM(branch_name))=UPPER(TRIM(?)) AND semester=? AND UPPER(TRIM(section_name))=UPPER(TRIM(?))",Long.class,required(b,"course"),required(b,"branch"),Integer.parseInt(required(b,"semester")),required(b,"section"));
    if(ids.isEmpty()) throw bad("No active students match this course selection.");
    for(long id:ids) db.update("INSERT INTO fee_assignments(student_id,title,amount,due_date,created_by) VALUES(?,?,?,?,?)",id,title,amount,due.isBlank()?null:LocalDate.parse(due),actor);
    return Map.of("message","Fee assigned","students",ids.size());
  }

  @GetMapping("/student/fees")
  List<Map<String,Object>> studentFees(HttpServletRequest r){ return db.queryForList("SELECT f.id,f.title,f.amount,f.due_date dueDate,f.status,f.created_at createdAt FROM fee_assignments f WHERE f.student_id=? ORDER BY f.status DESC,f.due_date NULLS LAST,f.created_at DESC",studentId(r)); }

  @GetMapping("/student/fees/{id}/receipt")
  ResponseEntity<byte[]> receipt(@PathVariable long id, HttpServletRequest r) {
    Map<String,Object> f=fee(id,studentId(r));
    if(!"PAID".equalsIgnoreCase(String.valueOf(f.get("STATUS")))) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Receipt is available after payment.");
    Map<String,Object> p=db.queryForMap("SELECT razorpay_payment_id paymentId,paid_at paidAt FROM fee_payments WHERE fee_id=? AND student_id=? AND status='PAID' ORDER BY paid_at DESC LIMIT 1",id,studentId(r));
    String text="CampusIQ Fee Receipt\n\nReceipt ID: FEE-"+id+"\nFee: "+f.get("TITLE")+"\nAmount: INR "+f.get("AMOUNT")+"\nStatus: PAID\nPayment ID: "+p.get("PAYMENTID")+"\nPaid on: "+p.get("PAIDAT");
    return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=fee-receipt-"+id+".txt").body(text.getBytes(StandardCharsets.UTF_8));
  }

  @PostMapping("/student/fees/{id}/order")
  Map<String,Object> order(@PathVariable long id,HttpServletRequest r){
    requireKeys(); long student=studentId(r);
    Map<String,Object> f=fee(id,student); if(!"PENDING".equalsIgnoreCase(String.valueOf(f.get("STATUS")))) throw bad("This fee is already paid.");
    long paise=Math.round(((Number)f.get("AMOUNT")).doubleValue()*100);
    try {
      String auth=Base64.getEncoder().encodeToString((keyId+":"+keySecret).getBytes(StandardCharsets.UTF_8));
      String body=json.writeValueAsString(Map.of("amount",paise,"currency","INR","receipt","fee_"+id+"_"+System.currentTimeMillis(),"notes",Map.of("feeId",id,"studentId",student)));
      java.net.http.HttpRequest req=java.net.http.HttpRequest.newBuilder(URI.create("https://api.razorpay.com/v1/orders")).header("Authorization","Basic "+auth).header("Content-Type","application/json").POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)).build();
      Map<?,?> out=json.readValue(HttpClient.newHttpClient().send(req,HttpResponse.BodyHandlers.ofString()).body(),Map.class); String orderId=String.valueOf(out.get("id"));
      db.update("INSERT INTO fee_payments(fee_id,student_id,razorpay_order_id,amount) VALUES(?,?,?,?)",id,student,orderId,paise/100.0);
      return Map.of("keyId",keyId,"orderId",orderId,"amount",paise,"currency","INR","feeId",id);
    }catch(Exception e){throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Razorpay order could not be created.");}
  }

  @PostMapping("/student/fees/verify")
  Map<String,Object> verify(@RequestBody Map<String,Object> b,HttpServletRequest r){
    requireKeys(); long student=studentId(r); String oid=required(b,"razorpayOrderId"),pid=required(b,"razorpayPaymentId"),sig=required(b,"razorpaySignature");
    try { if(!hmac(oid+"|"+pid,keySecret).equals(sig)) throw bad("Payment signature verification failed."); Map<String,Object> p=db.queryForMap("SELECT fee_id FROM fee_payments WHERE razorpay_order_id=? AND student_id=? AND status='CREATED'",oid,student); db.update("UPDATE fee_payments SET razorpay_payment_id=?,status='PAID',paid_at=CURRENT_TIMESTAMP WHERE razorpay_order_id=?",pid,oid); db.update("UPDATE fee_assignments SET status='PAID' WHERE id=?",p.get("FEE_ID")); return Map.of("message","Payment verified successfully."); }
    catch(ResponseStatusException e){throw e;}catch(Exception e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Payment verification failed.");}
  }
  private Map<String,Object> fee(long id,long student){try{return db.queryForMap("SELECT * FROM fee_assignments WHERE id=? AND student_id=?",id,student);}catch(Exception e){throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Fee not found.");}}
  private static long studentId(HttpServletRequest r){Object value=r.getAttribute("authenticatedStudentId");try{return value instanceof Number n?n.longValue():Long.parseLong(String.valueOf(value));}catch(Exception e){throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Student session is invalid.");}}
  private void requireKeys(){if(keyId.isBlank()||keySecret.isBlank())throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Razorpay is not configured. Set RAZORPAY_KEY_ID and RAZORPAY_KEY_SECRET.");}
  private static String required(Map<String,Object>b,String k){String v=String.valueOf(b.getOrDefault(k,"")).trim();if(v.isBlank())throw bad(k+" is required.");return v;}
  private static long longValue(Object v){try{return Long.parseLong(String.valueOf(v));}catch(Exception e){throw bad("Valid student is required.");}}
  private static double number(Object v){try{return Double.parseDouble(String.valueOf(v));}catch(Exception e){throw bad("Valid amount is required.");}}
  private static ResponseStatusException bad(String m){return new ResponseStatusException(HttpStatus.BAD_REQUEST,m);}
  private static String hmac(String value,String secret)throws Exception{Mac m=Mac.getInstance("HmacSHA256");m.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));StringBuilder s=new StringBuilder();for(byte x:m.doFinal(value.getBytes(StandardCharsets.UTF_8)))s.append(String.format("%02x",x));return s.toString();}
}

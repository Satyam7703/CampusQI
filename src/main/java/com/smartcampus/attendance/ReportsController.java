package com.smartcampus.attendance;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.io.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@RestController
@RequestMapping("/api/reports")
class ReportsController {
  private final JdbcTemplate db;
  ReportsController(JdbcTemplate db) { this.db = db; }

  @GetMapping("/analytics")
  Map<String,Object> analytics(@RequestParam(required=false) String from,
                               @RequestParam(required=false) String to,
                               @RequestParam(required=false) String course,
                               @RequestParam(required=false) String branch,
                               @RequestParam(required=false) Integer semester,
                               @RequestParam(required=false) String section,
                               @RequestParam(required=false) String subject,
                               HttpServletRequest request) {
    Filters f = filters(from,to,course,branch,semester,section,subject,request);
    String where = f.where;
    List<Object> args = new ArrayList<>(f.args);
    String sessionFrom = " FROM attendance_sessions s JOIN timetable t ON t.id=s.timetable_id JOIN classrooms c ON c.id=s.classroom_id ";
    Map<String,Object> totals = db.queryForMap("SELECT COUNT(DISTINCT s.id) sessions, COUNT(DISTINCT CASE WHEN ar.status='PRESENT' THEN ar.id END) presentRecords, COUNT(DISTINCT ar.id) markedRecords "+sessionFrom+" LEFT JOIN attendance_records ar ON ar.session_id=s.id "+where, args.toArray());

    String bySubjectSql = "SELECT t.subject_name subject, COUNT(DISTINCT s.id) sessions, " +
      "COUNT(DISTINCT CASE WHEN ar.status='PRESENT' THEN CONCAT(s.id,'-',st.id) END) presentSlots, " +
      "COUNT(DISTINCT CONCAT(s.id,'-',st.id)) eligibleSlots, " +
      "CASE WHEN COUNT(DISTINCT CONCAT(s.id,'-',st.id))=0 THEN 0 ELSE ROUND(100.0*COUNT(DISTINCT CASE WHEN ar.status='PRESENT' THEN CONCAT(s.id,'-',st.id) END)/COUNT(DISTINCT CONCAT(s.id,'-',st.id)),2) END attendancePercentage " +
      sessionFrom + " JOIN students st ON st.status='ACTIVE' AND st.course=t.course AND st.branch_name=t.branch_name AND st.semester=t.semester AND st.section_name=t.section_name " +
      "LEFT JOIN attendance_records ar ON ar.session_id=s.id AND ar.student_id=st.id " + where + " GROUP BY t.subject_name ORDER BY t.subject_name";
    List<Map<String,Object>> bySubject = db.queryForList(bySubjectSql,args.toArray());

    String lowSql = "SELECT st.id studentId, st.roll_no rollNo, st.full_name studentName, st.course, st.branch_name branch, st.semester, st.section_name section, " +
      "COUNT(DISTINCT s.id) eligibleSessions, COUNT(DISTINCT CASE WHEN ar.status='PRESENT' THEN s.id END) presentSessions, " +
      "CASE WHEN COUNT(DISTINCT s.id)=0 THEN 0 ELSE ROUND(100.0*COUNT(DISTINCT CASE WHEN ar.status='PRESENT' THEN s.id END)/COUNT(DISTINCT s.id),2) END attendancePercentage " +
      "FROM students st JOIN timetable t ON t.course=st.course AND t.branch_name=st.branch_name AND t.semester=st.semester AND t.section_name=st.section_name " +
      "JOIN attendance_sessions s ON s.timetable_id=t.id LEFT JOIN attendance_records ar ON ar.session_id=s.id AND ar.student_id=st.id " +
      where.replace("s.","s.") + " AND st.status='ACTIVE' GROUP BY st.id,st.roll_no,st.full_name,st.course,st.branch_name,st.semester,st.section_name " +
      "HAVING COUNT(DISTINCT s.id)>0 AND 100.0*COUNT(DISTINCT CASE WHEN ar.status='PRESENT' THEN s.id END)/COUNT(DISTINCT s.id)<75 ORDER BY attendancePercentage,st.full_name LIMIT 100";
    List<Map<String,Object>> lowAttendance = db.queryForList(lowSql,args.toArray());
    long expected=0, present=0;
    for(Map<String,Object> row:bySubject){ expected+=number(row.get("ELIGIBLESLOTS"),row.get("eligibleSlots")); present+=number(row.get("PRESENTSLOTS"),row.get("presentSlots")); }
    double rate=expected==0?0:Math.round(10000.0*present/expected)/100.0;
    Map<String,Object> result=new LinkedHashMap<>();
    result.put("sessions", number(totals.get("SESSIONS"),totals.get("sessions")));
    result.put("presentRecords",number(totals.get("PRESENTRECORDS"),totals.get("presentRecords")));
    result.put("markedRecords",number(totals.get("MARKEDRECORDS"),totals.get("markedRecords")));
    result.put("eligibleSlots",expected); result.put("attendancePercentage",rate);
    result.put("bySubject",bySubject); result.put("lowAttendance",lowAttendance);
    result.put("filters",Map.of("from",f.from==null?"":f.from.toString(),"to",f.to==null?"":f.to.toString()));
    return result;
  }

  @GetMapping(value="/export.csv", produces="text/csv")
  ResponseEntity<byte[]> export(@RequestParam(required=false) String from,
                                @RequestParam(required=false) String to,
                                @RequestParam(required=false) String course,
                                @RequestParam(required=false) String branch,
                                @RequestParam(required=false) Integer semester,
                                @RequestParam(required=false) String section,
                                @RequestParam(required=false) String subject,
                                HttpServletRequest request) {
    Filters f=filters(from,to,course,branch,semester,section,subject,request);
    String sql="SELECT s.session_date sessionDate,t.course,t.branch_name branch,t.semester,t.section_name section,t.subject_name subject,st.roll_no rollNo,st.full_name studentName,COALESCE(ar.status,'ABSENT') attendanceStatus,ar.marked_at markedAt,c.room_code room "+
      "FROM attendance_sessions s JOIN timetable t ON t.id=s.timetable_id JOIN classrooms c ON c.id=s.classroom_id "+
      "JOIN students st ON st.status='ACTIVE' AND st.course=t.course AND st.branch_name=t.branch_name AND st.semester=t.semester AND st.section_name=t.section_name "+
      "LEFT JOIN attendance_records ar ON ar.session_id=s.id AND ar.student_id=st.id "+f.where+" ORDER BY s.session_date DESC,t.subject_name,st.roll_no";
    List<Map<String,Object>> rows=db.queryForList(sql,f.args.toArray());
    StringBuilder csv=new StringBuilder("Date,Course,Branch,Semester,Section,Subject,Roll Number,Student,Attendance,Marked At,Room\r\n");
    for(Map<String,Object> row:rows){
      csv.append(cell(row,"SESSIONDATE","sessionDate")).append(',').append(cell(row,"COURSE","course")).append(',').append(cell(row,"BRANCH","branch")).append(',').append(cell(row,"SEMESTER","semester")).append(',').append(cell(row,"SECTION","section")).append(',').append(cell(row,"SUBJECT","subject")).append(',').append(cell(row,"ROLLNO","rollNo")).append(',').append(cell(row,"STUDENTNAME","studentName")).append(',').append(cell(row,"ATTENDANCESTATUS","attendanceStatus")).append(',').append(cell(row,"MARKEDAT","markedAt")).append(',').append(cell(row,"ROOM","room")).append("\r\n");
    }
    return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=campus-attendance-report.csv").contentType(new MediaType("text","csv",StandardCharsets.UTF_8)).body(csv.toString().getBytes(StandardCharsets.UTF_8));
  }

  private static final List<String> EXPORT_HEADERS = List.of("Date","Course","Branch","Semester","Section","Subject","Roll Number","Student","Attendance","Marked At","Room");

  @GetMapping(value="/export.xlsx", produces="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
  ResponseEntity<byte[]> exportExcel(@RequestParam(required=false) String from,
                                @RequestParam(required=false) String to,
                                @RequestParam(required=false) String course,
                                @RequestParam(required=false) String branch,
                                @RequestParam(required=false) Integer semester,
                                @RequestParam(required=false) String section,
                                @RequestParam(required=false) String subject,
                                HttpServletRequest request) {
    Filters f=filters(from,to,course,branch,semester,section,subject,request);
    List<Map<String,Object>> rows=exportRows(f);
    try {
      byte[] file=makeXlsx(rows);
      return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=campus-attendance-report.xlsx")
        .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).body(file);
    } catch(IOException ex) { throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,"Could not generate Excel report."); }
  }

  @GetMapping(value="/export.pdf", produces="application/pdf")
  ResponseEntity<byte[]> exportPdf(@RequestParam(required=false) String from,
                                @RequestParam(required=false) String to,
                                @RequestParam(required=false) String course,
                                @RequestParam(required=false) String branch,
                                @RequestParam(required=false) Integer semester,
                                @RequestParam(required=false) String section,
                                @RequestParam(required=false) String subject,
                                HttpServletRequest request) {
    Filters f=filters(from,to,course,branch,semester,section,subject,request);
    List<Map<String,Object>> rows=exportRows(f);
    try {
      byte[] file=makePdf(rows, f);
      return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=campus-attendance-report.pdf")
        .contentType(MediaType.APPLICATION_PDF).body(file);
    } catch(IOException ex) { throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,"Could not generate PDF report."); }
  }

  private List<Map<String,Object>> exportRows(Filters f) {
    String sql="SELECT s.session_date sessionDate,t.course,t.branch_name branch,t.semester,t.section_name section,t.subject_name subject,st.roll_no rollNo,st.full_name studentName,COALESCE(ar.status,'ABSENT') attendanceStatus,ar.marked_at markedAt,c.room_code room "+
      "FROM attendance_sessions s JOIN timetable t ON t.id=s.timetable_id JOIN classrooms c ON c.id=s.classroom_id "+
      "JOIN students st ON st.status='ACTIVE' AND st.course=t.course AND st.branch_name=t.branch_name AND st.semester=t.semester AND st.section_name=t.section_name "+
      "LEFT JOIN attendance_records ar ON ar.session_id=s.id AND ar.student_id=st.id "+f.where+" ORDER BY s.session_date DESC,t.subject_name,st.roll_no";
    return db.queryForList(sql,f.args.toArray());
  }

  private List<String> exportValues(Map<String,Object> r) {
    return List.of(str(r,"SESSIONDATE","sessionDate"),str(r,"COURSE","course"),str(r,"BRANCH","branch"),str(r,"SEMESTER","semester"),str(r,"SECTION","section"),str(r,"SUBJECT","subject"),str(r,"ROLLNO","rollNo"),str(r,"STUDENTNAME","studentName"),str(r,"ATTENDANCESTATUS","attendanceStatus"),str(r,"MARKEDAT","markedAt"),str(r,"ROOM","room"));
  }
  private String str(Map<String,Object> r,String upper,String lower){Object v=r.containsKey(upper)?r.get(upper):r.get(lower);return v==null?"":String.valueOf(v);}
  private byte[] makeXlsx(List<Map<String,Object>> rows) throws IOException {
    ByteArrayOutputStream out=new ByteArrayOutputStream();
    try(ZipOutputStream zip=new ZipOutputStream(out,StandardCharsets.UTF_8)) {
      putZip(zip,"[Content_Types].xml","<?xml version=\"1.0\" encoding=\"UTF-8\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/><Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/></Types>");
      putZip(zip,"_rels/.rels","<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>");
      putZip(zip,"xl/workbook.xml","<?xml version=\"1.0\" encoding=\"UTF-8\"?><workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\"Attendance Report\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>");
      putZip(zip,"xl/_rels/workbook.xml.rels","<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/></Relationships>");
      StringBuilder sheet=new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews><sheetData>");
      sheet.append("<row r=\"1\">");for(int c=0;c<EXPORT_HEADERS.size();c++)appendCell(sheet,c+1,1,EXPORT_HEADERS.get(c));sheet.append("</row>");
      int rowNum=2;for(Map<String,Object> r:rows){sheet.append("<row r=\"").append(rowNum).append("\">");List<String> values=exportValues(r);for(int c=0;c<values.size();c++)appendCell(sheet,c+1,rowNum,values.get(c));sheet.append("</row>");rowNum++;}
      sheet.append("</sheetData><autoFilter ref=\"A1:K").append(Math.max(1,rowNum-1)).append("\"/></worksheet>");putZip(zip,"xl/worksheets/sheet1.xml",sheet.toString());
    }return out.toByteArray();
  }
  private void appendCell(StringBuilder b,int col,int row,String value){b.append("<c r=\"").append(colName(col)).append(row).append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">").append(xml(value)).append("</t></is></c>");}
  private String colName(int n){StringBuilder s=new StringBuilder();while(n>0){int r=(n-1)%26;s.insert(0,(char)('A'+r));n=(n-1)/26;}return s.toString();}
  private String xml(String s){return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&apos;");}
  private void putZip(ZipOutputStream zip,String name,String value)throws IOException{zip.putNextEntry(new ZipEntry(name));zip.write(value.getBytes(StandardCharsets.UTF_8));zip.closeEntry();}

  private byte[] makePdf(List<Map<String,Object>> rows,Filters f)throws IOException{
    List<String> lines=new ArrayList<>();lines.add("CampusIQ - Attendance Report");lines.add("Date range: "+(f.from==null?"Any":f.from)+" to "+(f.to==null?"Any":f.to)+" | Rows: "+rows.size());lines.add("Date | Course | Branch | Sem | Sec | Subject | Roll | Student | Status | Room");
    for(Map<String,Object> r:rows){List<String> v=exportValues(r);String line=v.get(0)+" | "+v.get(1)+" | "+v.get(2)+" | "+v.get(3)+" | "+v.get(4)+" | "+v.get(5)+" | "+v.get(6)+" | "+v.get(7)+" | "+v.get(8)+" | "+v.get(10);lines.add(line);}
    List<List<String>> pages=new ArrayList<>();for(int i=0;i<lines.size();i+=48)pages.add(lines.subList(i,Math.min(i+48,lines.size())));if(pages.isEmpty())pages.add(List.of("CampusIQ - Attendance Report","No attendance rows matched the selected filters."));
    List<byte[]> objects=new ArrayList<>();int pageCount=pages.size();int fontId=3+pageCount*2;objects.add(bytes("<< /Type /Catalog /Pages 2 0 R >>"));StringBuilder kids=new StringBuilder("<< /Type /Pages /Kids [");for(int i=0;i<pageCount;i++)kids.append(3+i*2).append(" 0 R ");kids.append("] /Count ").append(pageCount).append(" >>");objects.add(bytes(kids.toString()));
    for(int i=0;i<pageCount;i++){int pageId=3+i*2,streamId=pageId+1;StringBuilder content=new StringBuilder("BT /F1 7 Tf 28 570 Td 9 TL ");for(String line:pages.get(i)){content.append("(").append(pdfEscape(ascii(line,145))).append(") Tj T* ");}content.append("ET");byte[] cb=bytes(content.toString());objects.add(bytes("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 842 595] /Resources << /Font << /F1 "+fontId+" 0 R >> >> /Contents "+streamId+" 0 R >>"));objects.add(concat(bytes("<< /Length "+cb.length+" >>\nstream\n"),cb,bytes("\nendstream")));}
    objects.add(bytes("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"));ByteArrayOutputStream pdf=new ByteArrayOutputStream();pdf.write(bytes("%PDF-1.4\n%\u00e2\u00e3\u00cf\u00d3\n"));List<Integer> offsets=new ArrayList<>();offsets.add(0);for(int i=0;i<objects.size();i++){offsets.add(pdf.size());pdf.write(bytes((i+1)+" 0 obj\n"));pdf.write(objects.get(i));pdf.write(bytes("\nendobj\n"));}int xref=pdf.size();pdf.write(bytes("xref\n0 "+(objects.size()+1)+"\n0000000000 65535 f \n"));for(int i=1;i<offsets.size();i++)pdf.write(bytes(String.format(Locale.ROOT,"%010d 00000 n \n",offsets.get(i))));pdf.write(bytes("trailer\n<< /Size "+(objects.size()+1)+" /Root 1 0 R >>\nstartxref\n"+xref+"\n%%EOF"));return pdf.toByteArray();
  }
  private byte[] bytes(String s){return s.getBytes(StandardCharsets.ISO_8859_1);}
  private byte[] concat(byte[]...parts)throws IOException{ByteArrayOutputStream o=new ByteArrayOutputStream();for(byte[]p:parts)o.write(p);return o.toByteArray();}
  private String ascii(String s,int max){String clean=s==null?"":s.replaceAll("[^\\x20-\\x7E]","?");return clean.length()>max?clean.substring(0,max-3)+"...":clean;}
  private String pdfEscape(String s){return s.replace("\\","\\\\").replace("(","\\(").replace(")","\\)");}

  private Filters filters(String from,String to,String course,String branch,Integer semester,String section,String subject,HttpServletRequest req){
    LocalDate start=parseDate(from,"from"), end=parseDate(to,"to");
    if(start!=null&&end!=null&&start.isAfter(end)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"From date must be on or before To date.");
    StringBuilder w=new StringBuilder(" WHERE 1=1"); List<Object> a=new ArrayList<>();
    if(start!=null){w.append(" AND s.session_date>=?");a.add(java.sql.Date.valueOf(start));}
    if(end!=null){w.append(" AND s.session_date<=?");a.add(java.sql.Date.valueOf(end));}
    add(w,a,"t.course",course); add(w,a,"t.branch_name",branch); if(semester!=null){if(semester<1||semester>20)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Semester must be between 1 and 20.");w.append(" AND t.semester=?");a.add(semester);} add(w,a,"t.section_name",section); add(w,a,"t.subject_name",subject);
    return new Filters(w.toString(),a,start,end);
  }
  private LocalDate parseDate(String s,String label){if(s==null||s.isBlank())return null;try{return LocalDate.parse(s.trim());}catch(DateTimeParseException ex){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid "+label+" date. Use YYYY-MM-DD.");}}
  private void add(StringBuilder w,List<Object>a,String column,String value){if(value!=null&&!value.isBlank()){if(value.length()>100)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Filter is too long.");w.append(" AND ").append(column).append("=?");a.add(value.trim());}}
  private long number(Object... values){for(Object v:values)if(v instanceof Number n)return n.longValue();return 0;}
  private String cell(Map<String,Object> row,String upper,String lower){Object v=row.containsKey(upper)?row.get(upper):row.get(lower);String s=v==null?"":String.valueOf(v);if(!s.isEmpty()&&"=+-@\t\r".indexOf(s.charAt(0))>=0)s="'"+s;return "\""+s.replace("\"","\"\"")+"\"";}
  private record Filters(String where,List<Object> args,LocalDate from,LocalDate to){}
}

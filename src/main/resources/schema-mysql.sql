CREATE TABLE IF NOT EXISTS students (id BIGINT AUTO_INCREMENT PRIMARY KEY, roll_no VARCHAR(40) NOT NULL UNIQUE, full_name VARCHAR(120) NOT NULL, email VARCHAR(160) UNIQUE, password_hash VARCHAR(100), course VARCHAR(50) NOT NULL, branch_name VARCHAR(80) NOT NULL, semester INT NOT NULL, section_name VARCHAR(10) NOT NULL, status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE');
CREATE TABLE IF NOT EXISTS classrooms (id BIGINT AUTO_INCREMENT PRIMARY KEY, room_code VARCHAR(30) NOT NULL UNIQUE, building VARCHAR(80) NOT NULL, floor_name VARCHAR(30), latitude DOUBLE, longitude DOUBLE, radius_m INT NOT NULL DEFAULT 50, active BOOLEAN NOT NULL DEFAULT TRUE);
CREATE TABLE IF NOT EXISTS timetable (id BIGINT AUTO_INCREMENT PRIMARY KEY, course VARCHAR(50) NOT NULL, branch_name VARCHAR(80) NOT NULL, semester INT NOT NULL, section_name VARCHAR(10) NOT NULL, subject_name VARCHAR(120) NOT NULL, teacher_name VARCHAR(120) NOT NULL, classroom_id BIGINT NOT NULL, day_of_week VARCHAR(15) NOT NULL, start_time VARCHAR(5) NOT NULL, end_time VARCHAR(5) NOT NULL, FOREIGN KEY(classroom_id) REFERENCES classrooms(id));
CREATE TABLE IF NOT EXISTS attendance_sessions (id BIGINT AUTO_INCREMENT PRIMARY KEY, timetable_id BIGINT NOT NULL, classroom_id BIGINT NOT NULL, session_date DATE NOT NULL, start_time VARCHAR(5) NOT NULL, end_time VARCHAR(5) NOT NULL, token VARCHAR(80) NOT NULL UNIQUE, token_expires_at TIMESTAMP NOT NULL, status VARCHAR(20) NOT NULL DEFAULT 'OPEN', created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, FOREIGN KEY(timetable_id) REFERENCES timetable(id), FOREIGN KEY(classroom_id) REFERENCES classrooms(id));
CREATE TABLE IF NOT EXISTS attendance_records (id BIGINT AUTO_INCREMENT PRIMARY KEY, student_id BIGINT NOT NULL, session_id BIGINT NOT NULL, status VARCHAR(20) NOT NULL, verification_mode VARCHAR(40) NOT NULL, marked_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, note VARCHAR(250), UNIQUE KEY uq_student_session(student_id, session_id), FOREIGN KEY(student_id) REFERENCES students(id), FOREIGN KEY(session_id) REFERENCES attendance_sessions(id));
CREATE TABLE IF NOT EXISTS audit_logs (id BIGINT AUTO_INCREMENT PRIMARY KEY, action_name VARCHAR(80) NOT NULL, actor VARCHAR(120) NOT NULL, detail VARCHAR(500), created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP);

CREATE TABLE IF NOT EXISTS password_reset_tokens (id BIGINT AUTO_INCREMENT PRIMARY KEY, email VARCHAR(160) NOT NULL, token_hash VARCHAR(64) NOT NULL UNIQUE, expires_at TIMESTAMP NOT NULL, used BOOLEAN NOT NULL DEFAULT FALSE, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP);
CREATE TABLE IF NOT EXISTS password_reset_otps (id BIGINT AUTO_INCREMENT PRIMARY KEY, email VARCHAR(160) NOT NULL, otp_hash VARCHAR(64) NOT NULL, expires_at TIMESTAMP NOT NULL, used BOOLEAN NOT NULL DEFAULT FALSE, attempts INT NOT NULL DEFAULT 0, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP);


-- Store face embeddings only; raw camera frames are not persisted.
CREATE TABLE IF NOT EXISTS face_templates (student_id BIGINT PRIMARY KEY, descriptor_json VARCHAR(12000) NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, FOREIGN KEY(student_id) REFERENCES students(id));


-- Persistent in-app alerts and email delivery history. One low-attendance alert per student per day.
CREATE TABLE IF NOT EXISTS attendance_notifications (id BIGINT AUTO_INCREMENT PRIMARY KEY, student_id BIGINT NOT NULL, alert_type VARCHAR(40) NOT NULL, period_key VARCHAR(20) NOT NULL, title VARCHAR(180) NOT NULL, message VARCHAR(1000) NOT NULL, recipient_email VARCHAR(160), email_status VARCHAR(30) NOT NULL DEFAULT 'IN_APP_ONLY', created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, CONSTRAINT uq_student_alert_period UNIQUE(student_id, alert_type, period_key), FOREIGN KEY(student_id) REFERENCES students(id));
CREATE INDEX IF NOT EXISTS idx_notifications_student_created ON attendance_notifications(student_id, created_at);

-- Indexes for attendance reports and common joins.
CREATE INDEX idx_sessions_timetable_date ON attendance_sessions(timetable_id, session_date);
CREATE INDEX idx_attendance_session_status ON attendance_records(session_id, status);
CREATE INDEX idx_attendance_student_marked ON attendance_records(student_id, marked_at);
CREATE INDEX idx_timetable_cohort ON timetable(course, branch_name, semester, section_name);


-- Teacher directory used by the administration module.
CREATE TABLE IF NOT EXISTS teachers (id BIGINT AUTO_INCREMENT PRIMARY KEY, full_name VARCHAR(120) NOT NULL, email VARCHAR(160) NOT NULL UNIQUE, department VARCHAR(120) NOT NULL, status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE', created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP);

CREATE TABLE IF NOT EXISTS staff_users (id BIGINT AUTO_INCREMENT PRIMARY KEY, full_name VARCHAR(120) NOT NULL, email VARCHAR(160) NOT NULL UNIQUE, password_hash VARCHAR(100) NOT NULL, role VARCHAR(20) NOT NULL, department VARCHAR(120), status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE', created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP);
CREATE TABLE IF NOT EXISTS staff_sessions (id BIGINT AUTO_INCREMENT PRIMARY KEY, token_hash VARCHAR(64) NOT NULL UNIQUE, user_id BIGINT NOT NULL, role VARCHAR(20) NOT NULL, expires_at TIMESTAMP NOT NULL, revoked BOOLEAN NOT NULL DEFAULT FALSE, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, FOREIGN KEY(user_id) REFERENCES staff_users(id));


-- A teacher may access only timetable entries explicitly assigned by an administrator.
CREATE TABLE IF NOT EXISTS teacher_timetable_assignments (id BIGINT AUTO_INCREMENT PRIMARY KEY, teacher_user_id BIGINT NOT NULL, timetable_id BIGINT NOT NULL, assigned_by VARCHAR(160), created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, CONSTRAINT uq_teacher_timetable UNIQUE(teacher_user_id,timetable_id), FOREIGN KEY(teacher_user_id) REFERENCES staff_users(id), FOREIGN KEY(timetable_id) REFERENCES timetable(id));

CREATE TABLE IF NOT EXISTS institute_settings (setting_key VARCHAR(80) PRIMARY KEY, setting_value VARCHAR(120) NOT NULL);
INSERT IGNORE INTO institute_settings(setting_key,setting_value) VALUES('MAX_MARKS','100');
CREATE TABLE IF NOT EXISTS student_marks (id BIGINT AUTO_INCREMENT PRIMARY KEY, student_id BIGINT NOT NULL, timetable_id BIGINT NOT NULL, assessment_type VARCHAR(20) NOT NULL, marks DECIMAL(8,2) NOT NULL, entered_by BIGINT NOT NULL, updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, CONSTRAINT uq_student_assessment UNIQUE(student_id,timetable_id,assessment_type), FOREIGN KEY(student_id) REFERENCES students(id), FOREIGN KEY(timetable_id) REFERENCES timetable(id), FOREIGN KEY(entered_by) REFERENCES staff_users(id));

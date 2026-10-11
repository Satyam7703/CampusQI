CREATE TABLE IF NOT EXISTS students (id BIGINT AUTO_INCREMENT PRIMARY KEY, roll_no VARCHAR(40) NOT NULL UNIQUE, full_name VARCHAR(120) NOT NULL, email VARCHAR(160) UNIQUE, password_hash VARCHAR(100), course VARCHAR(50) NOT NULL, branch_name VARCHAR(80) NOT NULL, semester INT NOT NULL, section_name VARCHAR(10) NOT NULL, status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE');
CREATE TABLE IF NOT EXISTS classrooms (id BIGINT AUTO_INCREMENT PRIMARY KEY, room_code VARCHAR(30) NOT NULL UNIQUE, building VARCHAR(80) NOT NULL, floor_name VARCHAR(30), latitude DOUBLE, longitude DOUBLE, radius_m INT NOT NULL DEFAULT 50, active BOOLEAN NOT NULL DEFAULT TRUE);
CREATE TABLE IF NOT EXISTS timetable (id BIGINT AUTO_INCREMENT PRIMARY KEY, course VARCHAR(50) NOT NULL, branch_name VARCHAR(80) NOT NULL, semester INT NOT NULL, section_name VARCHAR(10) NOT NULL, subject_name VARCHAR(120) NOT NULL, teacher_name VARCHAR(120) NOT NULL, classroom_id BIGINT NOT NULL, day_of_week VARCHAR(15) NOT NULL, start_time VARCHAR(5) NOT NULL, end_time VARCHAR(5) NOT NULL, FOREIGN KEY(classroom_id) REFERENCES classrooms(id));
CREATE TABLE IF NOT EXISTS attendance_sessions (id BIGINT AUTO_INCREMENT PRIMARY KEY, timetable_id BIGINT NOT NULL, classroom_id BIGINT NOT NULL, session_date DATE NOT NULL, start_time VARCHAR(5) NOT NULL, end_time VARCHAR(5) NOT NULL, token VARCHAR(80) NOT NULL UNIQUE, token_expires_at TIMESTAMP NOT NULL, status VARCHAR(20) NOT NULL DEFAULT 'OPEN', created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, FOREIGN KEY(timetable_id) REFERENCES timetable(id), FOREIGN KEY(classroom_id) REFERENCES classrooms(id));
CREATE TABLE IF NOT EXISTS attendance_records (id BIGINT AUTO_INCREMENT PRIMARY KEY, student_id BIGINT NOT NULL, session_id BIGINT NOT NULL, status VARCHAR(20) NOT NULL, verification_mode VARCHAR(40) NOT NULL, marked_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, note VARCHAR(250), CONSTRAINT uq_student_session UNIQUE(student_id, session_id), FOREIGN KEY(student_id) REFERENCES students(id), FOREIGN KEY(session_id) REFERENCES attendance_sessions(id));
CREATE TABLE IF NOT EXISTS audit_logs (id BIGINT AUTO_INCREMENT PRIMARY KEY, action_name VARCHAR(80) NOT NULL, actor VARCHAR(120) NOT NULL, detail VARCHAR(500), created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP);
CREATE TABLE IF NOT EXISTS campus_notices (id BIGINT AUTO_INCREMENT PRIMARY KEY, title VARCHAR(180) NOT NULL, message VARCHAR(2000) NOT NULL, audience VARCHAR(20) NOT NULL DEFAULT 'ALL', created_by VARCHAR(160) NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP);
CREATE INDEX IF NOT EXISTS idx_campus_notices_created ON campus_notices(created_at);

CREATE TABLE IF NOT EXISTS password_reset_tokens (id BIGINT AUTO_INCREMENT PRIMARY KEY, email VARCHAR(160) NOT NULL, account_type VARCHAR(20) NOT NULL DEFAULT 'STUDENT', token_hash VARCHAR(64) NOT NULL UNIQUE, expires_at TIMESTAMP NOT NULL, used BOOLEAN NOT NULL DEFAULT FALSE, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP);
CREATE TABLE IF NOT EXISTS password_reset_otps (id BIGINT AUTO_INCREMENT PRIMARY KEY, email VARCHAR(160) NOT NULL, account_type VARCHAR(20) NOT NULL DEFAULT 'STUDENT', otp_hash VARCHAR(64) NOT NULL, expires_at TIMESTAMP NOT NULL, used BOOLEAN NOT NULL DEFAULT FALSE, attempts INT NOT NULL DEFAULT 0, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP);
ALTER TABLE password_reset_tokens ADD COLUMN IF NOT EXISTS account_type VARCHAR(20) NOT NULL DEFAULT 'STUDENT';
ALTER TABLE password_reset_otps ADD COLUMN IF NOT EXISTS account_type VARCHAR(20) NOT NULL DEFAULT 'STUDENT';
CREATE INDEX IF NOT EXISTS idx_reset_otp_email ON password_reset_otps(email);
CREATE INDEX IF NOT EXISTS idx_reset_email ON password_reset_tokens(email);

-- Store face embeddings only; raw camera frames are not persisted.
CREATE TABLE IF NOT EXISTS face_templates (student_id BIGINT PRIMARY KEY, descriptor_json VARCHAR(12000) NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, FOREIGN KEY(student_id) REFERENCES students(id));


-- Persistent in-app alerts and email delivery history. One low-attendance alert per student per day.
CREATE TABLE IF NOT EXISTS attendance_notifications (id BIGINT AUTO_INCREMENT PRIMARY KEY, student_id BIGINT NOT NULL, alert_type VARCHAR(40) NOT NULL, period_key VARCHAR(20) NOT NULL, title VARCHAR(180) NOT NULL, message VARCHAR(1000) NOT NULL, recipient_email VARCHAR(160), email_status VARCHAR(30) NOT NULL DEFAULT 'IN_APP_ONLY', created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, CONSTRAINT uq_student_alert_period UNIQUE(student_id, alert_type, period_key), FOREIGN KEY(student_id) REFERENCES students(id));
CREATE INDEX IF NOT EXISTS idx_notifications_student_created ON attendance_notifications(student_id, created_at);

-- Indexes for attendance reports and common joins.
CREATE INDEX IF NOT EXISTS idx_sessions_timetable_date ON attendance_sessions(timetable_id, session_date);
CREATE INDEX IF NOT EXISTS idx_attendance_session_status ON attendance_records(session_id, status);
CREATE INDEX IF NOT EXISTS idx_attendance_student_marked ON attendance_records(student_id, marked_at);
CREATE INDEX IF NOT EXISTS idx_timetable_cohort ON timetable(course, branch_name, semester, section_name);


-- Teacher directory used by the administration module.
CREATE TABLE IF NOT EXISTS teachers (id BIGINT AUTO_INCREMENT PRIMARY KEY, full_name VARCHAR(120) NOT NULL, email VARCHAR(160) NOT NULL UNIQUE, department VARCHAR(120) NOT NULL, status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE', created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP);

CREATE TABLE IF NOT EXISTS staff_users (id BIGINT AUTO_INCREMENT PRIMARY KEY, full_name VARCHAR(120) NOT NULL, email VARCHAR(160) NOT NULL UNIQUE, password_hash VARCHAR(100) NOT NULL, role VARCHAR(20) NOT NULL, department VARCHAR(120), status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE', created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP);
CREATE TABLE IF NOT EXISTS staff_sessions (id BIGINT AUTO_INCREMENT PRIMARY KEY, token_hash VARCHAR(64) NOT NULL UNIQUE, user_id BIGINT NOT NULL, role VARCHAR(20) NOT NULL, expires_at TIMESTAMP NOT NULL, revoked BOOLEAN NOT NULL DEFAULT FALSE, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, FOREIGN KEY(user_id) REFERENCES staff_users(id));


-- A teacher may access only timetable entries explicitly assigned by an administrator.
CREATE TABLE IF NOT EXISTS teacher_timetable_assignments (id BIGINT AUTO_INCREMENT PRIMARY KEY, teacher_user_id BIGINT NOT NULL, timetable_id BIGINT NOT NULL, assigned_by VARCHAR(160), created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, CONSTRAINT uq_teacher_timetable UNIQUE(teacher_user_id,timetable_id), FOREIGN KEY(teacher_user_id) REFERENCES staff_users(id), FOREIGN KEY(timetable_id) REFERENCES timetable(id));

-- Institute-wide assessment policy and teacher-entered marks.
CREATE TABLE IF NOT EXISTS institute_settings (setting_key VARCHAR(80) PRIMARY KEY, setting_value VARCHAR(120) NOT NULL);
INSERT INTO institute_settings(setting_key,setting_value) SELECT 'MAX_MARKS','100' WHERE NOT EXISTS (SELECT 1 FROM institute_settings WHERE setting_key='MAX_MARKS');
INSERT INTO institute_settings(setting_key,setting_value) SELECT 'CT_MAX_MARKS','20' WHERE NOT EXISTS (SELECT 1 FROM institute_settings WHERE setting_key='CT_MAX_MARKS');
INSERT INTO institute_settings(setting_key,setting_value) SELECT 'SESSIONAL_MAX_MARKS','20' WHERE NOT EXISTS (SELECT 1 FROM institute_settings WHERE setting_key='SESSIONAL_MAX_MARKS');
INSERT INTO institute_settings(setting_key,setting_value) SELECT 'PUT_MAX_MARKS','60' WHERE NOT EXISTS (SELECT 1 FROM institute_settings WHERE setting_key='PUT_MAX_MARKS');
CREATE TABLE IF NOT EXISTS student_marks (id BIGINT AUTO_INCREMENT PRIMARY KEY, student_id BIGINT NOT NULL, timetable_id BIGINT NOT NULL, assessment_type VARCHAR(20) NOT NULL, marks DECIMAL(8,2) NOT NULL, entered_by BIGINT NOT NULL, updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, CONSTRAINT uq_student_assessment UNIQUE(student_id,timetable_id,assessment_type), FOREIGN KEY(student_id) REFERENCES students(id), FOREIGN KEY(timetable_id) REFERENCES timetable(id), FOREIGN KEY(entered_by) REFERENCES staff_users(id));
CREATE INDEX IF NOT EXISTS idx_marks_student ON student_marks(student_id);
CREATE TABLE IF NOT EXISTS fee_assignments (id BIGINT AUTO_INCREMENT PRIMARY KEY, student_id BIGINT NOT NULL, title VARCHAR(160) NOT NULL, amount DECIMAL(12,2) NOT NULL, due_date DATE, status VARCHAR(20) NOT NULL DEFAULT 'PENDING', created_by VARCHAR(160) NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, FOREIGN KEY(student_id) REFERENCES students(id));
CREATE TABLE IF NOT EXISTS fee_payments (id BIGINT AUTO_INCREMENT PRIMARY KEY, fee_id BIGINT NOT NULL, student_id BIGINT NOT NULL, razorpay_order_id VARCHAR(100) NOT NULL UNIQUE, razorpay_payment_id VARCHAR(100), amount DECIMAL(12,2) NOT NULL, status VARCHAR(20) NOT NULL DEFAULT 'CREATED', paid_at TIMESTAMP, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, FOREIGN KEY(fee_id) REFERENCES fee_assignments(id), FOREIGN KEY(student_id) REFERENCES students(id));
CREATE INDEX IF NOT EXISTS idx_fees_student ON fee_assignments(student_id,status);

package com.smartcampus.attendance;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
class SecurityConfig implements WebMvcConfigurer {
  private final StaffRoleInterceptor staffRoleInterceptor;
  SecurityConfig(StaffRoleInterceptor staffRoleInterceptor) { this.staffRoleInterceptor = staffRoleInterceptor; }
  @Override public void addInterceptors(InterceptorRegistry registry) { registry.addInterceptor(staffRoleInterceptor).addPathPatterns("/api/**"); }
  @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }

  // Allows existing prototype databases to upgrade without requiring users to delete their data.
  @Bean CommandLineRunner schemaCompatibility(JdbcTemplate db) {
    return args -> {
      try { db.execute("ALTER TABLE students ADD COLUMN password_hash VARCHAR(100)"); } catch (Exception ignored) { }
      try { db.execute("CREATE TABLE IF NOT EXISTS password_reset_tokens (id BIGINT AUTO_INCREMENT PRIMARY KEY, email VARCHAR(160) NOT NULL, token_hash VARCHAR(64) NOT NULL UNIQUE, expires_at TIMESTAMP NOT NULL, used BOOLEAN NOT NULL DEFAULT FALSE, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)"); } catch (Exception ignored) { }
      try { db.execute("CREATE TABLE IF NOT EXISTS password_reset_otps (id BIGINT AUTO_INCREMENT PRIMARY KEY, email VARCHAR(160) NOT NULL, otp_hash VARCHAR(64) NOT NULL, expires_at TIMESTAMP NOT NULL, used BOOLEAN NOT NULL DEFAULT FALSE, attempts INT NOT NULL DEFAULT 0, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)"); } catch (Exception ignored) { }
      try { db.execute("CREATE TABLE IF NOT EXISTS staff_users (id BIGINT AUTO_INCREMENT PRIMARY KEY, full_name VARCHAR(120) NOT NULL, email VARCHAR(160) NOT NULL UNIQUE, password_hash VARCHAR(100) NOT NULL, role VARCHAR(20) NOT NULL, department VARCHAR(120), status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE', created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)"); } catch (Exception ignored) { }
      try { db.execute("CREATE TABLE IF NOT EXISTS staff_sessions (id BIGINT AUTO_INCREMENT PRIMARY KEY, token_hash VARCHAR(64) NOT NULL UNIQUE, user_id BIGINT NOT NULL, role VARCHAR(20) NOT NULL, expires_at TIMESTAMP NOT NULL, revoked BOOLEAN NOT NULL DEFAULT FALSE, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)"); } catch (Exception ignored) { }
      try { db.execute("CREATE TABLE IF NOT EXISTS teachers (id BIGINT AUTO_INCREMENT PRIMARY KEY, full_name VARCHAR(120) NOT NULL, email VARCHAR(160) NOT NULL UNIQUE, department VARCHAR(120) NOT NULL, status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE', created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)"); } catch (Exception ignored) { }
      try { db.execute("ALTER TABLE teachers ADD COLUMN password_hash VARCHAR(100)"); } catch (Exception ignored) { }
      try { db.execute("CREATE TABLE IF NOT EXISTS teacher_timetable_assignments (id BIGINT AUTO_INCREMENT PRIMARY KEY, teacher_user_id BIGINT NOT NULL, timetable_id BIGINT NOT NULL, assigned_by VARCHAR(160), created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, CONSTRAINT uq_teacher_timetable UNIQUE(teacher_user_id,timetable_id), FOREIGN KEY(teacher_user_id) REFERENCES staff_users(id), FOREIGN KEY(timetable_id) REFERENCES timetable(id))"); } catch (Exception ignored) { }
      try { db.execute("CREATE TABLE IF NOT EXISTS institute_settings (setting_key VARCHAR(80) PRIMARY KEY, setting_value VARCHAR(120) NOT NULL)"); } catch (Exception ignored) { }
      try { db.update("INSERT INTO institute_settings(setting_key,setting_value) SELECT 'MAX_MARKS','100' WHERE NOT EXISTS (SELECT 1 FROM institute_settings WHERE setting_key='MAX_MARKS')"); } catch (Exception ignored) { }
      try { db.execute("CREATE TABLE IF NOT EXISTS student_marks (id BIGINT AUTO_INCREMENT PRIMARY KEY, student_id BIGINT NOT NULL, timetable_id BIGINT NOT NULL, assessment_type VARCHAR(20) NOT NULL, marks DECIMAL(8,2) NOT NULL, entered_by BIGINT NOT NULL, updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, CONSTRAINT uq_student_assessment UNIQUE(student_id,timetable_id,assessment_type))"); } catch (Exception ignored) { }
    };
  }
}

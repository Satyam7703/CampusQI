package com.smartcampus.attendance;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
class SecurityConfig {
  @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }

  // Allows existing prototype databases to upgrade without requiring users to delete their data.
  @Bean CommandLineRunner schemaCompatibility(JdbcTemplate db) {
    return args -> {
      try { db.execute("ALTER TABLE students ADD COLUMN password_hash VARCHAR(100)"); } catch (Exception ignored) { }
      try { db.execute("CREATE TABLE IF NOT EXISTS password_reset_tokens (id BIGINT AUTO_INCREMENT PRIMARY KEY, email VARCHAR(160) NOT NULL, token_hash VARCHAR(64) NOT NULL UNIQUE, expires_at TIMESTAMP NOT NULL, used BOOLEAN NOT NULL DEFAULT FALSE, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)"); } catch (Exception ignored) { }
    };
  }
}

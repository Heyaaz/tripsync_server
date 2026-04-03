CREATE TABLE `users` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `nickname` VARCHAR(50) NOT NULL,
  `email` VARCHAR(255) NULL,
  `auth_provider` ENUM('kakao', 'google', 'guest') NOT NULL,
  `provider_user_id` VARCHAR(100) NULL,
  `profile_image_url` TEXT NULL,
  `admin_yn` CHAR(1) NOT NULL DEFAULT 'N',
  `is_guest` BOOLEAN NOT NULL DEFAULT FALSE,
  `del_yn` CHAR(1) NOT NULL DEFAULT 'N',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_users_provider` (`auth_provider`, `provider_user_id`),
  KEY `idx_users_email` (`email`),
  KEY `idx_users_del_yn` (`del_yn`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `tpti_results` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT UNSIGNED NOT NULL,
  `mobility_score` TINYINT UNSIGNED NOT NULL,
  `photo_score` TINYINT UNSIGNED NOT NULL,
  `budget_score` TINYINT UNSIGNED NOT NULL,
  `theme_score` TINYINT UNSIGNED NOT NULL,
  `character_name` VARCHAR(100) NOT NULL,
  `source_answers` JSON NOT NULL,
  `is_manually_adjusted` BOOLEAN NOT NULL DEFAULT FALSE,
  `del_yn` CHAR(1) NOT NULL DEFAULT 'N',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_tpti_results_user_id` (`user_id`),
  CONSTRAINT `fk_tpti_results_user`
    FOREIGN KEY (`user_id`) REFERENCES `users`(`id`)
    ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `trip_rooms` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `host_user_id` BIGINT UNSIGNED NOT NULL,
  `share_code` VARCHAR(12) NOT NULL,
  `destination` VARCHAR(100) NOT NULL,
  `trip_date` DATE NOT NULL,
  `status` ENUM('waiting', 'ready', 'completed') NOT NULL DEFAULT 'waiting',
  `del_yn` CHAR(1) NOT NULL DEFAULT 'N',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_trip_rooms_share_code` (`share_code`),
  KEY `idx_trip_rooms_host_user_id` (`host_user_id`),
  CONSTRAINT `fk_trip_rooms_host_user`
    FOREIGN KEY (`host_user_id`) REFERENCES `users`(`id`)
    ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `room_members` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `room_id` BIGINT UNSIGNED NOT NULL,
  `user_id` BIGINT UNSIGNED NOT NULL,
  `role` ENUM('host', 'member') NOT NULL,
  `joined_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `del_yn` CHAR(1) NOT NULL DEFAULT 'N',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_room_members_room_user` (`room_id`, `user_id`),
  KEY `idx_room_members_user_id` (`user_id`),
  CONSTRAINT `fk_room_members_room`
    FOREIGN KEY (`room_id`) REFERENCES `trip_rooms`(`id`)
    ON DELETE CASCADE,
  CONSTRAINT `fk_room_members_user`
    FOREIGN KEY (`user_id`) REFERENCES `users`(`id`)
    ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `room_member_profiles` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `room_id` BIGINT UNSIGNED NOT NULL,
  `user_id` BIGINT UNSIGNED NOT NULL,
  `tpti_result_id` BIGINT UNSIGNED NOT NULL,
  `mobility_score` TINYINT UNSIGNED NOT NULL,
  `photo_score` TINYINT UNSIGNED NOT NULL,
  `budget_score` TINYINT UNSIGNED NOT NULL,
  `theme_score` TINYINT UNSIGNED NOT NULL,
  `character_name` VARCHAR(100) NOT NULL,
  `del_yn` CHAR(1) NOT NULL DEFAULT 'N',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_room_member_profiles_room_user` (`room_id`, `user_id`),
  KEY `idx_room_member_profiles_tpti_result_id` (`tpti_result_id`),
  CONSTRAINT `fk_room_member_profiles_room`
    FOREIGN KEY (`room_id`) REFERENCES `trip_rooms`(`id`)
    ON DELETE CASCADE,
  CONSTRAINT `fk_room_member_profiles_user`
    FOREIGN KEY (`user_id`) REFERENCES `users`(`id`)
    ON DELETE RESTRICT,
  CONSTRAINT `fk_room_member_profiles_tpti_result`
    FOREIGN KEY (`tpti_result_id`) REFERENCES `tpti_results`(`id`)
    ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `conflict_maps` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `room_id` BIGINT UNSIGNED NOT NULL,
  `common_axes` JSON NOT NULL,
  `conflict_axes` JSON NOT NULL,
  `summary_text` TEXT NOT NULL,
  `del_yn` CHAR(1) NOT NULL DEFAULT 'N',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_conflict_maps_room_id` (`room_id`),
  CONSTRAINT `fk_conflict_maps_room`
    FOREIGN KEY (`room_id`) REFERENCES `trip_rooms`(`id`)
    ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `places` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `tour_api_id` VARCHAR(100) NOT NULL,
  `name` VARCHAR(255) NOT NULL,
  `address` VARCHAR(255) NOT NULL,
  `latitude` DECIMAL(10,7) NOT NULL,
  `longitude` DECIMAL(10,7) NOT NULL,
  `category` VARCHAR(100) NOT NULL,
  `image_url` TEXT NULL,
  `operating_hours` JSON NULL,
  `admission_fee` VARCHAR(100) NULL,
  `mobility_score` TINYINT UNSIGNED NOT NULL,
  `photo_score` TINYINT UNSIGNED NOT NULL,
  `budget_score` TINYINT UNSIGNED NOT NULL,
  `theme_score` TINYINT UNSIGNED NOT NULL,
  `metadata_tags` JSON NULL,
  `del_yn` CHAR(1) NOT NULL DEFAULT 'N',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_places_tour_api_id` (`tour_api_id`),
  KEY `idx_places_category` (`category`),
  KEY `idx_places_lat_lng` (`latitude`, `longitude`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `schedules` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `room_id` BIGINT UNSIGNED NOT NULL,
  `version` INT NOT NULL,
  `generation_input` JSON NOT NULL,
  `group_satisfaction` TINYINT UNSIGNED NOT NULL,
  `llm_provider` VARCHAR(50) NULL,
  `del_yn` CHAR(1) NOT NULL DEFAULT 'N',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_schedules_room_version` (`room_id`, `version`),
  KEY `idx_schedules_room_id` (`room_id`),
  CONSTRAINT `fk_schedules_room`
    FOREIGN KEY (`room_id`) REFERENCES `trip_rooms`(`id`)
    ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `schedule_slots` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `schedule_id` BIGINT UNSIGNED NOT NULL,
  `start_time` DATETIME NOT NULL,
  `end_time` DATETIME NOT NULL,
  `place_id` BIGINT UNSIGNED NOT NULL,
  `slot_type` ENUM('common', 'personal') NOT NULL,
  `target_user_id` BIGINT UNSIGNED NULL,
  `reason_axis` VARCHAR(20) NOT NULL,
  `order_index` INT NOT NULL,
  `del_yn` CHAR(1) NOT NULL DEFAULT 'N',
  PRIMARY KEY (`id`),
  KEY `idx_schedule_slots_schedule_id` (`schedule_id`),
  KEY `idx_schedule_slots_schedule_order` (`schedule_id`, `order_index`),
  KEY `idx_schedule_slots_target_user_id` (`target_user_id`),
  CONSTRAINT `fk_schedule_slots_schedule`
    FOREIGN KEY (`schedule_id`) REFERENCES `schedules`(`id`)
    ON DELETE CASCADE,
  CONSTRAINT `fk_schedule_slots_place`
    FOREIGN KEY (`place_id`) REFERENCES `places`(`id`)
    ON DELETE RESTRICT,
  CONSTRAINT `fk_schedule_slots_target_user`
    FOREIGN KEY (`target_user_id`) REFERENCES `users`(`id`)
    ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `satisfaction_scores` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `schedule_id` BIGINT UNSIGNED NOT NULL,
  `user_id` BIGINT UNSIGNED NOT NULL,
  `score` TINYINT UNSIGNED NOT NULL,
  `breakdown` JSON NOT NULL,
  `del_yn` CHAR(1) NOT NULL DEFAULT 'N',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_satisfaction_scores_schedule_user` (`schedule_id`, `user_id`),
  KEY `idx_satisfaction_scores_user_id` (`user_id`),
  CONSTRAINT `fk_satisfaction_scores_schedule`
    FOREIGN KEY (`schedule_id`) REFERENCES `schedules`(`id`)
    ON DELETE CASCADE,
  CONSTRAINT `fk_satisfaction_scores_user`
    FOREIGN KEY (`user_id`) REFERENCES `users`(`id`)
    ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

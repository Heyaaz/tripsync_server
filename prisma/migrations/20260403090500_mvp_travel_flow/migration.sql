-- DropForeignKey
ALTER TABLE `conflict_maps` DROP FOREIGN KEY `fk_conflict_maps_room`;

-- DropForeignKey
ALTER TABLE `room_member_profiles` DROP FOREIGN KEY `fk_room_member_profiles_room`;

-- DropForeignKey
ALTER TABLE `room_member_profiles` DROP FOREIGN KEY `fk_room_member_profiles_tpti_result`;

-- DropForeignKey
ALTER TABLE `room_member_profiles` DROP FOREIGN KEY `fk_room_member_profiles_user`;

-- DropForeignKey
ALTER TABLE `room_members` DROP FOREIGN KEY `fk_room_members_room`;

-- DropForeignKey
ALTER TABLE `room_members` DROP FOREIGN KEY `fk_room_members_user`;

-- DropForeignKey
ALTER TABLE `satisfaction_scores` DROP FOREIGN KEY `fk_satisfaction_scores_schedule`;

-- DropForeignKey
ALTER TABLE `satisfaction_scores` DROP FOREIGN KEY `fk_satisfaction_scores_user`;

-- DropForeignKey
ALTER TABLE `schedule_slots` DROP FOREIGN KEY `fk_schedule_slots_place`;

-- DropForeignKey
ALTER TABLE `schedule_slots` DROP FOREIGN KEY `fk_schedule_slots_schedule`;

-- DropForeignKey
ALTER TABLE `schedule_slots` DROP FOREIGN KEY `fk_schedule_slots_target_user`;

-- DropForeignKey
ALTER TABLE `schedules` DROP FOREIGN KEY `fk_schedules_room`;

-- DropForeignKey
ALTER TABLE `tpti_results` DROP FOREIGN KEY `fk_tpti_results_user`;

-- DropForeignKey
ALTER TABLE `trip_rooms` DROP FOREIGN KEY `fk_trip_rooms_host_user`;

-- DropIndex
DROP INDEX `uq_schedules_room_version` ON `schedules`;

-- DropIndex
DROP INDEX `idx_users_del_yn` ON `users`;

-- AlterTable
ALTER TABLE `conflict_maps` MODIFY `del_yn` ENUM('Y', 'N') NOT NULL DEFAULT 'N',
    MODIFY `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3);

-- AlterTable
ALTER TABLE `places` MODIFY `del_yn` ENUM('Y', 'N') NOT NULL DEFAULT 'N',
    MODIFY `updated_at` DATETIME(3) NOT NULL;

-- AlterTable
ALTER TABLE `room_member_profiles` MODIFY `del_yn` ENUM('Y', 'N') NOT NULL DEFAULT 'N',
    MODIFY `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3);

-- AlterTable
ALTER TABLE `room_members` MODIFY `joined_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    MODIFY `del_yn` ENUM('Y', 'N') NOT NULL DEFAULT 'N';

-- AlterTable
ALTER TABLE `satisfaction_scores` MODIFY `del_yn` ENUM('Y', 'N') NOT NULL DEFAULT 'N',
    MODIFY `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3);

-- AlterTable
ALTER TABLE `schedule_slots` ADD COLUMN `reason_text` VARCHAR(100) NULL,
    MODIFY `start_time` DATETIME(3) NOT NULL,
    MODIFY `end_time` DATETIME(3) NOT NULL,
    MODIFY `del_yn` ENUM('Y', 'N') NOT NULL DEFAULT 'N';

-- AlterTable
ALTER TABLE `schedules` ADD COLUMN `is_confirmed` BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN `option_type` ENUM('balanced', 'individual', 'discovery') NOT NULL,
    ADD COLUMN `summary` TEXT NULL,
    MODIFY `del_yn` ENUM('Y', 'N') NOT NULL DEFAULT 'N',
    MODIFY `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3);

-- AlterTable
ALTER TABLE `tpti_results` MODIFY `del_yn` ENUM('Y', 'N') NOT NULL DEFAULT 'N',
    MODIFY `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3);

-- AlterTable
ALTER TABLE `trip_rooms` MODIFY `del_yn` ENUM('Y', 'N') NOT NULL DEFAULT 'N',
    MODIFY `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3);

-- AlterTable
ALTER TABLE `users` MODIFY `admin_yn` ENUM('Y', 'N') NOT NULL DEFAULT 'N',
    MODIFY `del_yn` ENUM('Y', 'N') NOT NULL DEFAULT 'N',
    MODIFY `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3);

-- CreateIndex
CREATE UNIQUE INDEX `uq_schedules_room_version_option` ON `schedules`(`room_id`, `version`, `option_type`);

-- AddForeignKey
ALTER TABLE `tpti_results` ADD CONSTRAINT `tpti_results_user_id_fkey` FOREIGN KEY (`user_id`) REFERENCES `users`(`id`) ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE `trip_rooms` ADD CONSTRAINT `trip_rooms_host_user_id_fkey` FOREIGN KEY (`host_user_id`) REFERENCES `users`(`id`) ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE `room_members` ADD CONSTRAINT `room_members_room_id_fkey` FOREIGN KEY (`room_id`) REFERENCES `trip_rooms`(`id`) ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE `room_members` ADD CONSTRAINT `room_members_user_id_fkey` FOREIGN KEY (`user_id`) REFERENCES `users`(`id`) ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE `room_member_profiles` ADD CONSTRAINT `room_member_profiles_room_id_fkey` FOREIGN KEY (`room_id`) REFERENCES `trip_rooms`(`id`) ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE `room_member_profiles` ADD CONSTRAINT `room_member_profiles_user_id_fkey` FOREIGN KEY (`user_id`) REFERENCES `users`(`id`) ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE `room_member_profiles` ADD CONSTRAINT `room_member_profiles_tpti_result_id_fkey` FOREIGN KEY (`tpti_result_id`) REFERENCES `tpti_results`(`id`) ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE `conflict_maps` ADD CONSTRAINT `conflict_maps_room_id_fkey` FOREIGN KEY (`room_id`) REFERENCES `trip_rooms`(`id`) ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE `schedules` ADD CONSTRAINT `schedules_room_id_fkey` FOREIGN KEY (`room_id`) REFERENCES `trip_rooms`(`id`) ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE `schedule_slots` ADD CONSTRAINT `schedule_slots_schedule_id_fkey` FOREIGN KEY (`schedule_id`) REFERENCES `schedules`(`id`) ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE `schedule_slots` ADD CONSTRAINT `schedule_slots_place_id_fkey` FOREIGN KEY (`place_id`) REFERENCES `places`(`id`) ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE `schedule_slots` ADD CONSTRAINT `schedule_slots_target_user_id_fkey` FOREIGN KEY (`target_user_id`) REFERENCES `users`(`id`) ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE `satisfaction_scores` ADD CONSTRAINT `satisfaction_scores_schedule_id_fkey` FOREIGN KEY (`schedule_id`) REFERENCES `schedules`(`id`) ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE `satisfaction_scores` ADD CONSTRAINT `satisfaction_scores_user_id_fkey` FOREIGN KEY (`user_id`) REFERENCES `users`(`id`) ON DELETE RESTRICT ON UPDATE CASCADE;


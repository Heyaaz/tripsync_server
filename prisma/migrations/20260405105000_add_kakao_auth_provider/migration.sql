-- AlterTable
ALTER TABLE `users` MODIFY `auth_provider` ENUM('kakao', 'google', 'local', 'guest') NOT NULL;

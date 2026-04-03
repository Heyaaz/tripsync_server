-- AlterTable
ALTER TABLE `users` MODIFY `auth_provider` ENUM('google', 'guest') NOT NULL;


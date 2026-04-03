-- AlterTable
ALTER TABLE `users` ADD COLUMN `password_hash` VARCHAR(255) NULL,
    MODIFY `auth_provider` ENUM('google', 'local', 'guest') NOT NULL;

-- CreateIndex
CREATE UNIQUE INDEX `uq_users_email` ON `users`(`email`);


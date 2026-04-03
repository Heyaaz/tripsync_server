import { IsEmail, IsString, Length, Matches } from 'class-validator';

export class RegisterDto {
  @IsString()
  @Length(2, 12)
  nickname!: string;

  @IsEmail()
  email!: string;

  @IsString()
  @Length(8, 64)
  @Matches(/^(?=.*[A-Za-z])(?=.*\d).+$/)
  password!: string;
}

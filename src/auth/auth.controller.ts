import { Controller, Get, Post, Query, Body } from '@nestjs/common';
import { AuthProvider } from '../common/enums/domain.enums';
import { CreateGuestSessionDto } from './dto/create-guest-session.dto';
import { AuthService } from './auth.service';

@Controller('auth')
export class AuthController {
  constructor(private readonly authService: AuthService) {}

  @Get('kakao')
  getKakaoOAuthRedirect(@Query('redirectPath') redirectPath?: string) {
    return this.authService.getOAuthRedirect(AuthProvider.KAKAO, redirectPath);
  }

  @Get('kakao/callback')
  handleKakaoCallback(@Query() query: Record<string, string | undefined>) {
    return this.authService.handleOAuthCallback(AuthProvider.KAKAO, query);
  }

  @Get('google')
  getGoogleOAuthRedirect(@Query('redirectPath') redirectPath?: string) {
    return this.authService.getOAuthRedirect(AuthProvider.GOOGLE, redirectPath);
  }

  @Get('google/callback')
  handleGoogleCallback(@Query() query: Record<string, string | undefined>) {
    return this.authService.handleOAuthCallback(AuthProvider.GOOGLE, query);
  }

  @Post('guest')
  createGuestSession(@Body() dto: CreateGuestSessionDto) {
    return this.authService.createGuestSession(dto);
  }
}

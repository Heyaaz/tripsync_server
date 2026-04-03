import { Body, Controller, Get, Headers, HttpCode, HttpStatus, Post, Query, Res } from '@nestjs/common';
import { AuthProvider } from '../common/enums/domain.enums';
import { CreateGuestSessionDto } from './dto/create-guest-session.dto';
import { AuthService } from './auth.service';
import {
  buildCookieHeader,
  buildExpiredCookieHeader,
  getOauthStateCookieName,
  getSessionCookieName,
} from './session-token.util';

@Controller('auth')
export class AuthController {
  constructor(private readonly authService: AuthService) {}

  @Get('kakao')
  getKakaoOAuthRedirect(@Query('redirectPath') redirectPath: string | undefined, @Res() res: any) {
    const redirect = this.authService.getOAuthRedirect(AuthProvider.KAKAO, redirectPath);
    res.setHeader('Set-Cookie', buildCookieHeader(getOauthStateCookieName(), redirect.state, 600));
    return res.redirect(302, redirect.redirectUrl);
  }

  @Get('kakao/callback')
  async handleKakaoCallback(
    @Query() query: Record<string, string | undefined>,
    @Headers('cookie') cookieHeader: string | undefined,
    @Res() res: any,
  ) {
    const result = await this.authService.handleOAuthCallback(AuthProvider.KAKAO, query, cookieHeader);
    res.setHeader('Set-Cookie', [
      buildCookieHeader(getSessionCookieName(), result.sessionToken, 60 * 60 * 24 * 7),
      buildExpiredCookieHeader(getOauthStateCookieName()),
    ]);
    return res.redirect(302, result.redirectUrl);
  }

  @Get('google')
  getGoogleOAuthRedirect(@Query('redirectPath') redirectPath: string | undefined, @Res() res: any) {
    const redirect = this.authService.getOAuthRedirect(AuthProvider.GOOGLE, redirectPath);
    res.setHeader('Set-Cookie', buildCookieHeader(getOauthStateCookieName(), redirect.state, 600));
    return res.redirect(302, redirect.redirectUrl);
  }

  @Get('google/callback')
  async handleGoogleCallback(
    @Query() query: Record<string, string | undefined>,
    @Headers('cookie') cookieHeader: string | undefined,
    @Res() res: any,
  ) {
    const result = await this.authService.handleOAuthCallback(AuthProvider.GOOGLE, query, cookieHeader);
    res.setHeader('Set-Cookie', [
      buildCookieHeader(getSessionCookieName(), result.sessionToken, 60 * 60 * 24 * 7),
      buildExpiredCookieHeader(getOauthStateCookieName()),
    ]);
    return res.redirect(302, result.redirectUrl);
  }

  @Post('guest')
  @HttpCode(HttpStatus.CREATED)
  async createGuestSession(@Body() dto: CreateGuestSessionDto, @Res({ passthrough: true }) res: any) {
    const result = await this.authService.createGuestSession(dto);
    res.setHeader('Set-Cookie', buildCookieHeader(getSessionCookieName(), result.sessionToken, 60 * 60 * 24 * 7));
    return result.response;
  }

  @Post('logout')
  @HttpCode(HttpStatus.OK)
  logout(@Res({ passthrough: true }) res: any) {
    res.setHeader('Set-Cookie', buildExpiredCookieHeader(getSessionCookieName()));
    return {
      success: true,
      data: null,
      error: null,
      meta: {
        requestId: 'req_logout_local',
      },
    };
  }
}

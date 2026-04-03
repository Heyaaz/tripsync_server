import { Body, Controller, Get, HttpCode, HttpStatus, Param, ParseIntPipe, Post, UseGuards } from '@nestjs/common';
import { User } from '@prisma/client';
import { CurrentUser } from '../auth/current-user.decorator';
import { SessionAuthGuard } from '../auth/session-auth.guard';
import { CreateRoomDto } from './dto/create-room.dto';
import { JoinRoomDto } from './dto/join-room.dto';
import { RoomService } from './room.service';

@Controller('rooms')
export class RoomController {
  constructor(private readonly roomService: RoomService) {}

  @Post()
  @UseGuards(SessionAuthGuard)
  @HttpCode(HttpStatus.CREATED)
  createRoom(@Body() dto: CreateRoomDto, @CurrentUser() user: User) {
    return this.roomService.createRoom(dto, user);
  }

  @Get('share/:shareCode')
  getShareRoom(@Param('shareCode') shareCode: string) {
    return this.roomService.getShareRoom(shareCode);
  }

  @Post(':shareCode/join')
  @UseGuards(SessionAuthGuard)
  @HttpCode(HttpStatus.CREATED)
  joinRoom(@Param('shareCode') shareCode: string, @Body() dto: JoinRoomDto, @CurrentUser() user: User) {
    return this.roomService.joinRoom(shareCode, dto, user);
  }

  @Get(':id/members')
  @UseGuards(SessionAuthGuard)
  getMembers(@Param('id', ParseIntPipe) roomId: number, @CurrentUser() user: User) {
    return this.roomService.getMembers(roomId, user);
  }

  @Get(':id')
  @UseGuards(SessionAuthGuard)
  getRoom(@Param('id', ParseIntPipe) roomId: number, @CurrentUser() user: User) {
    return this.roomService.getRoom(roomId, user);
  }
}

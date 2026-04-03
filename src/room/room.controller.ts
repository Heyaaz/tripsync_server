import { Body, Controller, Get, Headers, HttpCode, HttpStatus, Param, ParseIntPipe, Post } from '@nestjs/common';
import { CreateRoomDto } from './dto/create-room.dto';
import { JoinRoomDto } from './dto/join-room.dto';
import { RoomService } from './room.service';

@Controller('rooms')
export class RoomController {
  constructor(private readonly roomService: RoomService) {}

  @Post()
  @HttpCode(HttpStatus.CREATED)
  createRoom(
    @Body() dto: CreateRoomDto,
    @Headers('authorization') authorization: string | undefined,
    @Headers('cookie') cookieHeader: string | undefined,
  ) {
    return this.roomService.createRoom(dto, authorization, cookieHeader);
  }

  @Get('share/:shareCode')
  getShareRoom(@Param('shareCode') shareCode: string) {
    return this.roomService.getShareRoom(shareCode);
  }

  @Post(':shareCode/join')
  @HttpCode(HttpStatus.CREATED)
  joinRoom(
    @Param('shareCode') shareCode: string,
    @Body() dto: JoinRoomDto,
    @Headers('authorization') authorization: string | undefined,
    @Headers('cookie') cookieHeader: string | undefined,
  ) {
    return this.roomService.joinRoom(shareCode, dto, authorization, cookieHeader);
  }

  @Get(':id/members')
  getMembers(
    @Param('id', ParseIntPipe) roomId: number,
    @Headers('authorization') authorization: string | undefined,
    @Headers('cookie') cookieHeader: string | undefined,
  ) {
    return this.roomService.getMembers(roomId, authorization, cookieHeader);
  }

  @Get(':id')
  getRoom(
    @Param('id', ParseIntPipe) roomId: number,
    @Headers('authorization') authorization: string | undefined,
    @Headers('cookie') cookieHeader: string | undefined,
  ) {
    return this.roomService.getRoom(roomId, authorization, cookieHeader);
  }
}

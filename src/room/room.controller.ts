import { Body, Controller, Get, Param, ParseIntPipe, Post } from '@nestjs/common';
import { CreateRoomDto } from './dto/create-room.dto';
import { JoinRoomDto } from './dto/join-room.dto';
import { RoomService } from './room.service';

@Controller('rooms')
export class RoomController {
  constructor(private readonly roomService: RoomService) {}

  @Post()
  createRoom(@Body() dto: CreateRoomDto) {
    return this.roomService.createRoom(dto);
  }

  @Get('share/:shareCode')
  getShareRoom(@Param('shareCode') shareCode: string) {
    return this.roomService.getShareRoom(shareCode);
  }

  @Post(':shareCode/join')
  joinRoom(@Param('shareCode') shareCode: string, @Body() dto: JoinRoomDto) {
    return this.roomService.joinRoom(shareCode, dto);
  }

  @Get(':id/members')
  getMembers(@Param('id', ParseIntPipe) roomId: number) {
    return this.roomService.getMembers(roomId);
  }
}

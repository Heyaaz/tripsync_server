export enum AuthProvider {
  KAKAO = 'kakao',
  GOOGLE = 'google',
  GUEST = 'guest',
}

export enum YnFlag {
  YES = 'Y',
  NO = 'N',
}

export enum TripRoomStatus {
  WAITING = 'waiting',
  READY = 'ready',
  COMPLETED = 'completed',
}

export enum RoomMemberRole {
  HOST = 'host',
  MEMBER = 'member',
}

export enum SlotType {
  COMMON = 'common',
  PERSONAL = 'personal',
}

export enum ReasonAxis {
  MOBILITY = 'mobility',
  PHOTO = 'photo',
  BUDGET = 'budget',
  THEME = 'theme',
  COMMON = 'common',
}

export enum RegenerationReason {
  LOW_SATISFACTION = 'low_satisfaction',
  NEW_MEMBER_JOINED = 'new_member_joined',
  MANUAL_RETRY = 'manual_retry',
}

# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
npm run start:dev          # 개발 서버 실행 (watch 모드)
npm run build              # 프로덕션 빌드
npm run lint               # ESLint (src/**/*.ts)
npm run test               # 전체 테스트
npx jest test/foo.spec.ts  # 단일 테스트 파일 실행
npx jest -t "test name"    # 테스트 이름으로 필터링
npx tsc --noEmit           # 타입 체크만 (빌드 없이)

npm run prisma:generate    # Prisma Client 재생성 (schema 변경 후 필수)
npm run prisma:migrate     # 개발 DB 마이그레이션
npm run prisma:push        # 스키마를 DB에 직접 반영 (마이그레이션 없이)
```

## Git Commit Convention

`type: summary` 단일 라인 형식. 허용 타입: `feat`, `fix`, `chore`, `docs`, `ref`, `style`, `test`  
파일은 명시적으로 스테이징 (`git add .` 금지).

## Architecture

### 모듈 구조

```
src/
├── auth/          # 세션 인증, Google OAuth, 로컬 로그인, 게스트
├── room/          # 여행방 생성·조회·참가, 멤버 관리
├── tpti/          # 여행 성향 테스트 (4축 점수 산출)
├── conflict/      # 갈등 지도 조회 (TPTI 축 간 gap 분석)
├── consensus/     # 합의 엔진 - 순수 도메인 로직, DB 의존 없음
├── schedule/      # 일정 생성·조회·확정·재생성
├── place/         # TourAPI 장소 정규화·점수 산출·상세정보 보강
├── tour-api/      # TourAPI 배치 동기화 (충남 areaCode=34)
├── llm/           # OpenAI/Gemini 일정 보정, 환경변수 기반 provider 선택
├── prisma/        # PrismaService 싱글톤
└── common/
    ├── dto/       # ApiResponse 래퍼 (ok() 헬퍼)
    ├── enums/     # domain.enums.ts — 모든 열거형 단일 파일
    ├── errors/    # DomainException, ApiExceptionFilter
    ├── env.util.ts      # readEnv() — process.env 읽기 공통 유틸
    ├── metadata.util.ts # readMetadataObject() — JSON 메타데이터 파싱 공통 유틸
    └── soft-delete/     # BaseSoftDeleteService, ACTIVE_DEL_YN, soft-delete 유틸
```

### 인증 흐름

- `SessionAuthGuard` + `@CurrentUser()` 데코레이터로 모든 인증 엔드포인트에 적용
- 쿠키(`session_token`) 또는 `Authorization: Bearer <token>` 헤더 양쪽을 지원
- 가드가 `request.user`에 Prisma `User` 객체를 주입 → 서비스 레이어는 직접 User를 받음 (더 이상 authorization 헤더를 직접 파싱하지 않음)

### 합의 엔진 (`ConsensusService`)

DB 의존이 없는 순수 도메인 서비스. 입력은 `MemberSnapshot[]`(TPTI 점수)과 `PlaceCandidate[]`(장소 후보), 출력은 3가지 `ScheduleOptionDraft[]`.

옵션 생성 순서:
1. `analyzeGroup()` → 축별 gap 분석, 공통축/충돌축 분류
2. `buildSlotTemplate()` → 갈등 심각도에 따라 슬롯 5·6·7개 결정
3. `buildIndividualSlotShapes()` + `buildIndividualTarget()` → 충돌축 기반 개인 슬롯 배분
4. `materializeOption()` x3 (Promise.all 병렬) → 장소 선택 + LLM 보정
5. LLM 보정 실패 시 결정론적 결과로 자동 폴백 (`fallbackUsed: true`)

### LLM 연동 (`LlmService`)

환경변수 `LLM_PROVIDER`, `OPENAI_API_KEY`, `GEMINI_API_KEY`로 제공자 선택. 키가 없으면 `null` 반환 → ConsensusService가 폴백 처리. 반환값은 `LlmRefineResult | null`.

### Soft Delete 패턴

모든 엔티티는 `delYn: YnFlag` 컬럼을 가짐. `BaseSoftDeleteService`를 상속하면 `this.activeWhere(filter)` 메서드로 `delYn: 'N'` 조건을 자동 주입. `PlaceService`는 직접 `ACTIVE_DEL_YN` 상수를 사용.

### 장소 점수 산출 (`PlaceService`)

TourAPI `contenttypeid` 기반 기본 점수 + 장소명 키워드 조정으로 4축(mobility/photo/budget/theme) 0~100 점수 산출. `upsertTourApiPlaces`는 배치 `findMany`로 기존 데이터를 한 번에 조회 후 `sourceModifiedTime` 비교로 불필요한 upsert 스킵.

### 공통 유틸

- `readEnv(name)` — `process.env` 값 읽기, `replace-me` 값은 `undefined` 처리
- `readMetadataObject(value)` — Prisma JsonValue를 `Record<string, unknown> | null`로 안전하게 캐스팅
- `ok(data)` — `{ success: true, data }` 응답 래퍼

## 참고 문서

- `../docs/API_SPEC.md` — REST API 명세 및 에러 코드
- `../docs/DB_SCHEMA.md` — MySQL 스키마 및 제약 조건
- `../docs/CONSENSUS_ENGINE.md` — 합의 엔진 상세 알고리즘 명세
- `../docs/TECH_SPEC.md` — 기술 스택 및 아키텍처 결정사항

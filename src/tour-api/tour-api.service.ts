import { HttpStatus, Injectable } from '@nestjs/common';
import { Prisma } from '@prisma/client';
import { User } from '@prisma/client';
import { AuthService } from '../auth/auth.service';
import { ok } from '../common/dto/api-response.dto';
import { DomainException } from '../common/errors/domain.exception';
import {
  PlaceService,
  TourApiCommonDetailItem,
  TourApiIntroDetailItem,
  TourApiPlaceItem,
} from '../place/place.service';
import { EnrichPlacesDto } from './dto/enrich-places.dto';

const CHUNGNAM_AREA_CODE = 34;
const SUPPORTED_CONTENT_TYPES = [12, 14, 15, 28, 32, 38, 39] as const;
const PAGE_SIZE = 100;
const DETAIL_FETCH_RETRY_COUNT = 2;
const ENRICH_SCAN_MULTIPLIER = 5;

@Injectable()
export class TourApiService {
  constructor(
    private readonly authService: AuthService,
    private readonly placeService: PlaceService,
  ) {}

  private readEnv(name: string) {
    const value = process.env[name]?.trim();
    if (!value || value === 'replace-me') {
      return undefined;
    }
    return value;
  }

  private getConfig() {
    const serviceKey = this.readEnv('TOUR_API_SERVICE_KEY');
    if (!serviceKey) {
      throw new DomainException(HttpStatus.BAD_GATEWAY, 'INVALID_REQUEST', 'TourAPI 서비스키가 설정되지 않았습니다.');
    }

    return {
      baseUrl: this.readEnv('TOUR_API_BASE_URL') ?? 'https://apis.data.go.kr/B551011/KorService2',
      serviceKey,
      mobileOs: this.readEnv('TOUR_API_MOBILE_OS') ?? 'ETC',
      mobileApp: this.readEnv('TOUR_API_MOBILE_APP') ?? 'TripSync',
      responseType: this.readEnv('TOUR_API_RESPONSE_TYPE') ?? 'json',
    };
  }

  private async withRetry<T>(task: () => Promise<T>, attempts = DETAIL_FETCH_RETRY_COUNT + 1): Promise<T> {
    let lastError: unknown;
    for (let index = 0; index < attempts; index += 1) {
      try {
        return await task();
      } catch (error) {
        lastError = error;
      }
    }
    throw lastError;
  }

  private async fetchAreaBasedListPage(contentTypeId: number, pageNo: number) {
    const config = this.getConfig();
    const params = new URLSearchParams({
      serviceKey: config.serviceKey,
      MobileOS: config.mobileOs,
      MobileApp: config.mobileApp,
      _type: config.responseType,
      numOfRows: String(PAGE_SIZE),
      pageNo: String(pageNo),
      arrange: 'A',
      areaCode: String(CHUNGNAM_AREA_CODE),
      contentTypeId: String(contentTypeId),
    });

    const response = await fetch(`${config.baseUrl}/areaBasedList2?${params.toString()}`);
    if (!response.ok) {
      throw new DomainException(HttpStatus.BAD_GATEWAY, 'OAUTH_PROVIDER_ERROR', 'TourAPI 호출에 실패했습니다.', {
        status: response.status,
        body: await response.text(),
        contentTypeId,
        pageNo,
      });
    }

    const payload = (await response.json()) as any;
    const body = payload?.response?.body;
    const header = payload?.response?.header;
    if (header?.resultCode !== '0000') {
      throw new DomainException(HttpStatus.BAD_GATEWAY, 'OAUTH_PROVIDER_ERROR', 'TourAPI 응답 오류가 발생했습니다.', {
        header,
        contentTypeId,
        pageNo,
      });
    }

    const rawItems = body?.items?.item;
    const items: TourApiPlaceItem[] = Array.isArray(rawItems)
      ? rawItems
      : rawItems
        ? [rawItems]
        : [];

    return {
      totalCount: Number(body?.totalCount ?? 0),
      items,
    };
  }

  private async fetchAllByContentType(contentTypeId: number) {
    const firstPage = await this.fetchAreaBasedListPage(contentTypeId, 1);
    const totalPages = Math.max(1, Math.ceil(firstPage.totalCount / PAGE_SIZE));
    const allItems = [...firstPage.items];

    for (let pageNo = 2; pageNo <= totalPages; pageNo += 1) {
      const page = await this.fetchAreaBasedListPage(contentTypeId, pageNo);
      allItems.push(...page.items);
    }

    return {
      totalCount: firstPage.totalCount,
      totalPages,
      items: allItems,
    };
  }

  private async fetchDetailCommon(contentId: string) {
    const config = this.getConfig();
    const params = new URLSearchParams({
      serviceKey: config.serviceKey,
      MobileOS: config.mobileOs,
      MobileApp: config.mobileApp,
      _type: config.responseType,
      contentId,
    });

    const response = await fetch(`${config.baseUrl}/detailCommon2?${params.toString()}`);
    if (!response.ok) {
      throw new DomainException(HttpStatus.BAD_GATEWAY, 'OAUTH_PROVIDER_ERROR', 'TourAPI 공통정보 조회에 실패했습니다.', {
        status: response.status,
        body: await response.text(),
        contentId,
      });
    }

    const payload = (await response.json()) as any;
    const header = payload?.response?.header;
    if (header?.resultCode !== '0000') {
      throw new DomainException(HttpStatus.BAD_GATEWAY, 'OAUTH_PROVIDER_ERROR', 'TourAPI 공통정보 응답 오류가 발생했습니다.', {
        header,
        contentId,
      });
    }

    const raw = payload?.response?.body?.items?.item;
    return (Array.isArray(raw) ? raw[0] : raw ?? null) as TourApiCommonDetailItem | null;
  }

  private async fetchDetailIntro(contentId: string, contentTypeId: string) {
    const config = this.getConfig();
    const params = new URLSearchParams({
      serviceKey: config.serviceKey,
      MobileOS: config.mobileOs,
      MobileApp: config.mobileApp,
      _type: config.responseType,
      contentId,
      contentTypeId,
    });

    const response = await fetch(`${config.baseUrl}/detailIntro2?${params.toString()}`);
    if (!response.ok) {
      throw new DomainException(HttpStatus.BAD_GATEWAY, 'OAUTH_PROVIDER_ERROR', 'TourAPI 소개정보 조회에 실패했습니다.', {
        status: response.status,
        body: await response.text(),
        contentId,
        contentTypeId,
      });
    }

    const payload = (await response.json()) as any;
    const header = payload?.response?.header;
    if (header?.resultCode !== '0000') {
      throw new DomainException(HttpStatus.BAD_GATEWAY, 'OAUTH_PROVIDER_ERROR', 'TourAPI 소개정보 응답 오류가 발생했습니다.', {
        header,
        contentId,
        contentTypeId,
      });
    }

    const raw = payload?.response?.body?.items?.item;
    return (Array.isArray(raw) ? raw[0] : raw ?? null) as TourApiIntroDetailItem | null;
  }

  async syncChungnamPlaces(user: User) {
    this.authService.assertHostUser(user);

    const byType: Array<{ contentTypeId: number; totalCount: number; synced: number; skipped: number; unchanged: number }> = [];
    let totalFetched = 0;
    let totalSynced = 0;
    let totalSkipped = 0;
    let totalUnchanged = 0;

    for (const contentTypeId of SUPPORTED_CONTENT_TYPES) {
      const result = await this.fetchAllByContentType(contentTypeId);
      const upserted = await this.placeService.upsertTourApiPlaces(result.items);
      byType.push({
        contentTypeId,
        totalCount: result.totalCount,
        synced: upserted.synced,
        skipped: upserted.skipped,
        unchanged: upserted.unchanged,
      });
      totalFetched += result.items.length;
      totalSynced += upserted.synced;
      totalSkipped += upserted.skipped;
      totalUnchanged += upserted.unchanged;
    }

    return ok({
      areaCode: CHUNGNAM_AREA_CODE,
      contentTypeIds: [...SUPPORTED_CONTENT_TYPES],
      totalFetched,
      totalSynced,
      totalSkipped,
      totalUnchanged,
      byType,
    });
  }

  private getContentTypeIdFromMetadata(metadataTags: Prisma.JsonValue | null) {
    if (!metadataTags || typeof metadataTags !== 'object' || Array.isArray(metadataTags)) {
      return null;
    }
    const value = (metadataTags as Record<string, unknown>).contentTypeId;
    return typeof value === 'string' ? value : typeof value === 'number' ? String(value) : null;
  }

  async enrichChungnamPlaces(user: User, dto?: EnrichPlacesDto) {
    this.authService.assertHostUser(user);

    const limit = dto?.limit ?? 50;
    const scanLimit = Math.max(limit, limit * ENRICH_SCAN_MULTIPLIER);
    const places = await this.placeService.listPlacesForEnrichment(scanLimit);
    const candidates = places.filter((place) => this.placeService.needsDetailEnrichment(place)).slice(0, limit);
    let enriched = 0;
    let skipped = 0;
    let failed = 0;

    for (const place of candidates) {
      const contentTypeId = this.getContentTypeIdFromMetadata(place.metadataTags);
      if (!contentTypeId) {
        skipped += 1;
        continue;
      }

      try {
        const common = await this.withRetry(() => this.fetchDetailCommon(place.tourApiId));
        const intro = await this.withRetry(() => this.fetchDetailIntro(place.tourApiId, contentTypeId));
        await this.placeService.enrichPlaceDetails(place.id, common, intro);
        enriched += 1;
      } catch {
        failed += 1;
      }
    }

    return ok({
      limit,
      scanned: places.length,
      queued: candidates.length,
      enriched,
      skipped: skipped + Math.max(0, places.length - candidates.length),
      failed,
    });
  }
}

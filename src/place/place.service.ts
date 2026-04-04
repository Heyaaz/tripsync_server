import { Injectable } from '@nestjs/common';
import { Prisma } from '@prisma/client';
import { PrismaService } from '../prisma/prisma.service';
import { readMetadataObject } from '../common/metadata.util';
import { ACTIVE_DEL_YN } from '../common/soft-delete/soft-delete.util';


export interface TourApiCommonDetailItem {
  contentid?: string;
  title?: string;
  homepage?: string;
  firstimage?: string;
  firstimage2?: string;
  addr1?: string;
  addr2?: string;
  tel?: string;
  overview?: string;
  mapx?: string;
  mapy?: string;
  modifiedtime?: string;
}

export interface TourApiIntroDetailItem extends Record<string, unknown> {
  contentid?: string;
  contenttypeid?: string;
}

export interface TourApiPlaceItem {
  contentid?: string;
  contenttypeid?: string;
  title?: string;
  addr1?: string;
  addr2?: string;
  mapx?: string;
  mapy?: string;
  firstimage?: string;
  cat1?: string;
  cat2?: string;
  cat3?: string;
  areacode?: string;
  sigungucode?: string;
  tel?: string;
  zipcode?: string;
  modifiedtime?: string;
}

const CONTENT_TYPE_CATEGORY: Record<string, string> = {
  '12': 'tourist_attraction',
  '14': 'cultural_facility',
  '15': 'festival',
  '28': 'leisure_sports',
  '32': 'accommodation',
  '38': 'shopping',
  '39': 'restaurant',
};

interface ParsedOperatingRange {
  openMinutes: number;
  closeMinutes: number;
  closesNextDay: boolean;
  raw: string;
  sourceKey: string;
}


@Injectable()
export class PlaceService {
  constructor(private readonly prisma: PrismaService) {}

  private clampScore(value: number) {
    return Math.max(0, Math.min(100, Math.round(value)));
  }

  private baseScores(contentTypeId: string) {
    switch (contentTypeId) {
      case '12':
        return { mobility: 60, photo: 60, budget: 45, theme: 45 };
      case '14':
        return { mobility: 40, photo: 55, budget: 45, theme: 60 };
      case '15':
        return { mobility: 55, photo: 75, budget: 55, theme: 65 };
      case '28':
        return { mobility: 85, photo: 55, budget: 60, theme: 45 };
      case '32':
        return { mobility: 15, photo: 35, budget: 70, theme: 35 };
      case '38':
        return { mobility: 55, photo: 60, budget: 70, theme: 75 };
      case '39':
        return { mobility: 30, photo: 50, budget: 55, theme: 65 };
      default:
        return { mobility: 50, photo: 50, budget: 50, theme: 50 };
    }
  }

  private keywordAdjustments(title: string) {
    let mobility = 0;
    let photo = 0;
    let budget = 0;
    let theme = 0;

    const lower = title.toLowerCase();
    const apply = (keywords: string[], fn: () => void) => {
      if (keywords.some((keyword) => lower.includes(keyword))) {
        fn();
      }
    };

    apply(['해변', '해수욕장', '산', '트레킹', '둘레길', '케이블카', '레일바이크'], () => {
      mobility += 15;
      photo += 5;
      theme -= 10;
    });
    apply(['수목원', '공원', '정원', '사찰', '숲', '휴양림'], () => {
      theme -= 20;
      photo += 5;
    });
    apply(['시장', '거리', '쇼핑', '아울렛'], () => {
      budget += 15;
      theme += 20;
    });
    apply(['전망대', '포토', '미디어', '빛축제', '벽화'], () => {
      photo += 20;
    });
    apply(['호텔', '리조트', '풀빌라', '스파'], () => {
      budget += 15;
      mobility -= 10;
    });
    apply(['맛집', '식당', '카페', '베이커리'], () => {
      budget += 5;
      theme += 10;
    });

    return { mobility, photo, budget, theme };
  }

  normalizeTourApiPlace(item: TourApiPlaceItem) {
    if (!item.contentid || !item.title || !item.mapx || !item.mapy) {
      return null;
    }

    const category = CONTENT_TYPE_CATEGORY[item.contenttypeid ?? ''] ?? 'unknown';
    const base = this.baseScores(item.contenttypeid ?? '');
    const adjust = this.keywordAdjustments(item.title);
    const address = [item.addr1, item.addr2].filter(Boolean).join(' ').trim() || '주소 정보 없음';
    const metadataTags = {
      contentTypeId: item.contenttypeid ?? null,
      category,
      areaCode: item.areacode ?? null,
      sigunguCode: item.sigungucode ?? null,
      cat1: item.cat1 ?? null,
      cat2: item.cat2 ?? null,
      cat3: item.cat3 ?? null,
      tel: item.tel ?? null,
      zipcode: item.zipcode ?? null,
      sourceModifiedTime: item.modifiedtime ?? null,
      source: 'tourapi',
    };

    return {
      tourApiId: item.contentid,
      name: item.title.trim(),
      address,
      latitude: Number(item.mapy),
      longitude: Number(item.mapx),
      category,
      imageUrl: item.firstimage ?? null,
      operatingHours: { status: 'unknown' } as Prisma.InputJsonValue,
      admissionFee: null,
      mobilityScore: this.clampScore(base.mobility + adjust.mobility),
      photoScore: this.clampScore(base.photo + adjust.photo),
      budgetScore: this.clampScore(base.budget + adjust.budget),
      themeScore: this.clampScore(base.theme + adjust.theme),
      metadataTags: metadataTags as Prisma.InputJsonValue,
      modifiedTime: item.modifiedtime ?? null,
    };
  }

  async upsertTourApiPlaces(items: TourApiPlaceItem[]) {
    let synced = 0;
    let skipped = 0;
    let unchanged = 0;

    const validItems = items
      .map((item) => this.normalizeTourApiPlace(item))
      .filter((n): n is NonNullable<typeof n> => n != null && !Number.isNaN(n.latitude) && !Number.isNaN(n.longitude));
    skipped = items.length - validItems.length;

    const existingList = await this.prisma.place.findMany({
      where: { tourApiId: { in: validItems.map((n) => n.tourApiId) } },
      select: { tourApiId: true, delYn: true, metadataTags: true },
    });
    const existingMap = new Map(existingList.map((e) => [e.tourApiId, e]));

    for (const normalized of validItems) {
      const existing = existingMap.get(normalized.tourApiId);
      const existingMetadata = readMetadataObject(existing?.metadataTags ?? null);
      const nextMetadata = readMetadataObject(normalized.metadataTags);
      if (
        existing &&
        existing.delYn === ACTIVE_DEL_YN &&
        existingMetadata?.sourceModifiedTime &&
        nextMetadata?.sourceModifiedTime &&
        existingMetadata.sourceModifiedTime === nextMetadata.sourceModifiedTime
      ) {
        unchanged += 1;
        continue;
      }

      const data = {
        name: normalized.name,
        address: normalized.address,
        latitude: normalized.latitude,
        longitude: normalized.longitude,
        category: normalized.category,
        imageUrl: normalized.imageUrl,
        operatingHours: normalized.operatingHours,
        admissionFee: normalized.admissionFee,
        mobilityScore: normalized.mobilityScore,
        photoScore: normalized.photoScore,
        budgetScore: normalized.budgetScore,
        themeScore: normalized.themeScore,
        metadataTags: normalized.metadataTags,
        delYn: ACTIVE_DEL_YN,
      };

      await this.prisma.place.upsert({
        where: { tourApiId: normalized.tourApiId },
        update: data,
        create: { tourApiId: normalized.tourApiId, ...data },
      });
      synced += 1;
    }

    return { synced, skipped, unchanged };
  }

  async listPlacesForEnrichment(limit: number) {
    return this.prisma.place.findMany({
      where: { delYn: ACTIVE_DEL_YN },
      orderBy: { id: 'asc' },
      take: limit,
    });
  }

  needsDetailEnrichment(place: { metadataTags: Prisma.JsonValue | null }) {
    const metadata = readMetadataObject(place.metadataTags);
    if (!metadata) {
      return true;
    }

    const detailEnrichedAt = this.toEpochMs(metadata.detailEnrichedAt);
    const sourceModifiedTime = this.parseSourceModifiedTime(metadata.sourceModifiedTime);
    if (!detailEnrichedAt) {
      return true;
    }
    if (!sourceModifiedTime) {
      return false;
    }

    return sourceModifiedTime > detailEnrichedAt;
  }

  private toEpochMs(value: unknown) {
    if (!value) {
      return null;
    }
    const epoch = Date.parse(String(value));
    return Number.isNaN(epoch) ? null : epoch;
  }

  private parseSourceModifiedTime(value: unknown) {
    if (!value) {
      return null;
    }

    const digits = String(value).replace(/\D/g, '');
    if (digits.length < 8) {
      return null;
    }

    const year = digits.slice(0, 4);
    const month = digits.slice(4, 6);
    const day = digits.slice(6, 8);
    const hour = digits.slice(8, 10) || '00';
    const minute = digits.slice(10, 12) || '00';
    const second = digits.slice(12, 14) || '00';
    const epoch = Date.parse(`${year}-${month}-${day}T${hour}:${minute}:${second}+09:00`);
    return Number.isNaN(epoch) ? null : epoch;
  }

  private toMinutes(hours: number, minutes: number) {
    return hours * 60 + minutes;
  }

  private parseTimeToken(token: string) {
    const normalized = token.trim().replace(/\s+/g, '');
    const colonMatch = normalized.match(/^(\d{1,2}):(\d{2})$/);
    if (colonMatch) {
      return {
        hours: Number(colonMatch[1]),
        minutes: Number(colonMatch[2]),
      };
    }

    const koreanMatch = normalized.match(/^(\d{1,2})시(?:(\d{1,2})분?)?$/);
    if (koreanMatch) {
      return {
        hours: Number(koreanMatch[1]),
        minutes: Number(koreanMatch[2] ?? 0),
      };
    }

    const compactMatch = normalized.match(/^(\d{2})(\d{2})$/);
    if (compactMatch) {
      return {
        hours: Number(compactMatch[1]),
        minutes: Number(compactMatch[2]),
      };
    }

    return null;
  }

  private parseOperatingRanges(raw: string, sourceKey: string): ParsedOperatingRange[] {
    const normalized = raw
      .replace(/[∼〜—–]/g, '~')
      .replace(/부터/g, '~')
      .replace(/까지/g, '')
      .replace(/\s+/g, ' ')
      .trim();

    if (!normalized) {
      return [];
    }

    const segments = normalized
      .split(/[,\n/]| 및 /)
      .map((segment) => segment.trim())
      .filter(Boolean);

    const ranges: ParsedOperatingRange[] = [];

    for (const segment of segments) {
      const match = segment.match(/(\d{1,2}(?::\d{2})?|\d{1,2}시(?:\d{1,2}분?)?|\d{4})\s*~\s*(\d{1,2}(?::\d{2})?|\d{1,2}시(?:\d{1,2}분?)?|\d{4})/);
      if (!match) {
        continue;
      }

      const open = this.parseTimeToken(match[1]);
      const close = this.parseTimeToken(match[2]);
      if (!open || !close) {
        continue;
      }

      let openMinutes = this.toMinutes(open.hours, open.minutes);
      let closeMinutes = this.toMinutes(close.hours, close.minutes);
      let closesNextDay = false;

      if (closeMinutes <= openMinutes) {
        closeMinutes += 24 * 60;
        closesNextDay = true;
      }

      ranges.push({
        openMinutes,
        closeMinutes,
        closesNextDay,
        raw: segment,
        sourceKey,
      });
    }

    return ranges;
  }

  private extractOperatingHours(intro: TourApiIntroDetailItem) {
    const hourFields = Object.entries(intro).filter(([key, value]) =>
      value && ['usetime', 'restdate', 'checkintime', 'checkouttime'].some((prefix) => key.toLowerCase().includes(prefix)),
    );

    if (hourFields.length === 0) {
      return { status: 'unknown' } as Prisma.InputJsonValue;
    }

    const rawFields = Object.fromEntries(hourFields.map(([key, value]) => [key, String(value)]));
    const joinedValues = Object.values(rawFields).join(' ');
    if (/(24시간|상시|연중무휴|항시)/.test(joinedValues)) {
      return {
        status: 'always',
        rawFields,
      } as Prisma.InputJsonValue;
    }

    const parsedRanges = hourFields.flatMap(([key, value]) => this.parseOperatingRanges(String(value), key));

    return parsedRanges.length > 0
      ? ({
          status: 'known',
          rawFields,
          entries: parsedRanges,
        } as unknown as Prisma.InputJsonValue)
      : ({
          status: 'partial',
          rawFields,
        } as Prisma.InputJsonValue);
  }

  private extractAdmissionFee(intro: TourApiIntroDetailItem) {
    const values = Object.entries(intro)
      .filter(([key, value]) => value && ['usefee', 'parkingfee'].some((prefix) => key.toLowerCase().includes(prefix)))
      .map(([key, value]) => `${key}: ${String(value)}`);
    return values.length > 0 ? values.join(' | ') : null;
  }

  async enrichPlaceDetails(
    placeId: bigint,
    common: TourApiCommonDetailItem | null,
    intro: TourApiIntroDetailItem | null,
  ) {
    const current = await this.prisma.place.findUnique({ where: { id: placeId } });
    if (!current) {
      return null;
    }

    const currentMetadata = readMetadataObject(current.metadataTags) ?? {};

    const mergedMetadata = {
      ...currentMetadata,
      tel: common?.tel ?? currentMetadata.tel ?? null,
      homepage: common?.homepage ?? currentMetadata.homepage ?? null,
      overview: common?.overview ?? currentMetadata.overview ?? null,
      detailEnrichedAt: new Date().toISOString(),
      introFields: intro ?? currentMetadata.introFields ?? null,
    } as Prisma.InputJsonValue;

    return this.prisma.place.update({
      where: { id: placeId },
      data: {
        name: common?.title?.trim() || current.name,
        address: [common?.addr1, common?.addr2].filter(Boolean).join(' ').trim() || current.address,
        latitude: common?.mapy ? Number(common.mapy) : current.latitude,
        longitude: common?.mapx ? Number(common.mapx) : current.longitude,
        imageUrl: common?.firstimage ?? current.imageUrl,
        operatingHours: intro
          ? this.extractOperatingHours(intro)
          : ((current.operatingHours ?? { status: 'unknown' }) as Prisma.InputJsonValue),
        admissionFee: intro ? this.extractAdmissionFee(intro) : current.admissionFee,
        metadataTags: mergedMetadata,
      },
    });
  }

  async findCandidatePlaces() {
    return this.prisma.place.findMany({
      where: { delYn: ACTIVE_DEL_YN },
      orderBy: { id: 'asc' },
    });
  }
}

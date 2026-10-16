import gcp from './gcp';
import posthog from './posthog';
import gcpBilling from './gcp-billing';
import github from './github';
import revenuecat from './revenuecat';
import gsc from './gsc';
import appleSearchAds from './apple-search-ads';
import discord from './discord';
import meta from './meta';
import youtube from './youtube';
import tiktok from './tiktok';
import remoteMcp from './remote-mcp';
import appStoreConnect from './app-store-connect';
import productMetrics from './product-metrics';

export const CONNECTOR_DOCS: Record<string, string> = {
  gcp,
  posthog,
  'gcp-billing': gcpBilling,
  github,
  revenuecat,
  gsc,
  'apple-search-ads': appleSearchAds,
  discord,
  meta,
  youtube,
  tiktok,
  'remote-mcp': remoteMcp,
  'app-store-connect': appStoreConnect,
  'product-metrics': productMetrics,
};

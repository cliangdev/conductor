import gcp from './gcp';
import posthog from './posthog';
import gcpBilling from './gcp-billing';
import github from './github';
import revenuecat from './revenuecat';
import gsc from './gsc';
import appleSearchAds from './apple-search-ads';

export const CONNECTOR_DOCS: Record<string, string> = {
  gcp,
  posthog,
  'gcp-billing': gcpBilling,
  github,
  revenuecat,
  gsc,
  'apple-search-ads': appleSearchAds,
};

import type { Metadata } from "next";
import { SiteShell } from "@/components/site/SiteChrome";
import { Hero } from "@/components/site/landing/Hero";
import { LogoStrip } from "@/components/site/landing/LogoStrip";
import { Departments } from "@/components/site/landing/Departments";
import { Platform } from "@/components/site/landing/Platform";
import { Loop } from "@/components/site/landing/Loop";
import { Tools } from "@/components/site/landing/Tools";
import { Trust } from "@/components/site/landing/Trust";
import { Source } from "@/components/site/landing/Source";
import { FinalCta } from "@/components/site/landing/FinalCta";
import "@/components/site/landing/landing.css";

export const metadata: Metadata = {
  // The public landing page is the site root, so it keeps the bare product name rather than the
  // layout's "%s · Conductor" template. The browser tab has to read exactly "Conductor".
  title: "Conductor",
  description:
    "Conductor is one workspace for a team of AI agents. Agents write the specs, ship the code, make and publish the marketing, and keep the company wiki current. A person approves before anything goes out, and results come back to improve the next draft.",
  alternates: { canonical: "/" },
};

export default function LandingPage() {
  return (
    <SiteShell>
      <Hero />
      <LogoStrip />
      <Departments />
      <Platform />
      <Loop />
      <Tools />
      <Trust />
      <Source />
      <FinalCta />
    </SiteShell>
  );
}

import {
  ArrowRight,
  Bot,
  Check,
  GitBranch,
  Hammer,
  KeyRound,
  Search,
  Wrench,
  type LucideIcon,
} from 'lucide-react'

import { PhonePreview } from '@/components/phone-preview'
import { PlayBadge } from '@/components/play-badge'
import { RunCharts } from '@/components/run-charts'
import { SiteFooter } from '@/components/site-footer'
import { SiteHeader } from '@/components/site-header'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import {
  Card,
  CardContent,
  CardDescription,
  CardFooter,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'

const features: { icon: LucideIcon; title: string; body: string }[] = [
  {
    icon: Search,
    title: 'Find a repo',
    body: 'Search GitHub, GitLab, and Codeberg from the console. Your model can rank the matches.',
  },
  {
    icon: GitBranch,
    title: 'Real git',
    body: 'Clone, diff, stage, commit, branch, pull, and push over HTTPS. All on the phone.',
  },
  {
    icon: Bot,
    title: 'An agent on your phone',
    body: 'It reads, greps, edits, and runs your tests, step by step, for up to 40 steps.',
  },
  {
    icon: KeyRound,
    title: 'Your model, your key',
    body: 'OpenAI, Anthropic, Gemini, Grok, OpenRouter, Cursor, or any compatible endpoint.',
  },
  {
    icon: Hammer,
    title: 'Build an APK',
    body: 'JavaScript and HTML are packed on the phone. Gradle projects compile on Cloud Run.',
  },
  {
    icon: Wrench,
    title: 'Revise on failure',
    body: 'A failed build goes back to the model with the log. Tap Revise, then build again.',
  },
]

const steps = [
  { n: '01', title: 'Open a project', body: 'Clone a repo, search for one, or open a folder on the phone.' },
  { n: '02', title: 'Vibe', body: 'Say what you want. Watch each tool call land in the chat.' },
  { n: '03', title: 'Build and install', body: 'Tap Build APK. Install it right there.' },
]

const freePlan = [
  'Every feature in the app',
  'Bring your own model key',
  'Builds on your own Cloud Run',
]

const proPlan = [
  'Everything in Free',
  'Hosted builds, no Cloud Run setup',
  'Supports AndVibe development',
]

export function Home() {
  return (
    <>
      <SiteHeader />
      <main>
        <section className="mx-auto grid max-w-5xl items-center gap-12 px-5 py-16 md:grid-cols-[1.1fr_1fr] md:py-24">
          <div className="space-y-6">
            <Badge variant="secondary" className="font-mono">
              Android · bring your own model
            </Badge>
            <h1 className="text-4xl font-semibold tracking-tight text-balance sm:text-5xl">
              Vibe code on your phone.
            </h1>
            <p className="max-w-md text-lg text-muted-foreground text-pretty">
              Clone a repo, let an AI agent edit it with your own key, and build an APK. No laptop
              needed.
            </p>
            <div className="flex flex-wrap items-center gap-3">
              <PlayBadge />
              <Button variant="ghost" asChild>
                <a href="#features">
                  See how it works <ArrowRight />
                </a>
              </Button>
            </div>
          </div>
          <PhonePreview />
        </section>

        <section id="features" className="scroll-mt-16 border-t">
          <div className="mx-auto max-w-5xl px-5 py-16">
            <h2 className="text-2xl font-semibold tracking-tight">A whole dev loop in your pocket</h2>
            <p className="mt-2 text-muted-foreground">
              Console, Files, Git, Vibe, and Build. Five tabs, one repo.
            </p>
            <div className="mt-8 grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
              {features.map(({ icon: Icon, title, body }) => (
                <Card key={title} className="gap-3">
                  <CardHeader>
                    <Icon className="size-5 text-link" />
                    <CardTitle className="pt-2">{title}</CardTitle>
                  </CardHeader>
                  <CardContent className="text-sm text-muted-foreground">{body}</CardContent>
                </Card>
              ))}
            </div>
          </div>
        </section>

        <section id="run" className="scroll-mt-16 border-t">
          <div className="mx-auto max-w-5xl px-5 py-16">
            <h2 className="text-2xl font-semibold tracking-tight">How a run goes</h2>
            <div className="mt-8 grid gap-6 sm:grid-cols-3">
              {steps.map((step) => (
                <div key={step.n} className="space-y-1">
                  <div className="font-mono text-sm text-chart-1">{step.n}</div>
                  <div className="font-medium">{step.title}</div>
                  <p className="text-sm text-muted-foreground">{step.body}</p>
                </div>
              ))}
            </div>
            <div className="mt-10">
              <RunCharts />
            </div>
            <p className="mt-6 text-sm text-muted-foreground">
              Keys stay in encrypted storage on the phone. Model calls go straight from the phone to
              your provider, and build uploads never include them.
            </p>
          </div>
        </section>

        <section id="pricing" className="scroll-mt-16 border-t">
          <div className="mx-auto max-w-5xl px-5 py-16">
            <h2 className="text-2xl font-semibold tracking-tight">Pricing</h2>
            <p className="mt-2 text-muted-foreground">Free to start. A subscription is on the way.</p>
            <div className="mt-8 grid gap-4 md:grid-cols-2">
              <Card>
                <CardHeader>
                  <CardTitle>Free</CardTitle>
                  <CardDescription>Everything you need to vibe.</CardDescription>
                </CardHeader>
                <CardContent className="space-y-4">
                  <div className="text-3xl font-semibold">$0</div>
                  <PlanList items={freePlan} />
                </CardContent>
                <CardFooter className="mt-auto">
                  <PlayBadge />
                </CardFooter>
              </Card>
              <Card className="border-link/40">
                <CardHeader>
                  <div className="flex items-center justify-between">
                    <CardTitle>Pro</CardTitle>
                    <Badge className="bg-link/15 text-link">Coming soon</Badge>
                  </div>
                  <CardDescription>For when you just want it to build.</CardDescription>
                </CardHeader>
                <CardContent className="space-y-4">
                  <div className="text-3xl font-semibold text-muted-foreground">Subscription</div>
                  <PlanList items={proPlan} />
                </CardContent>
                <CardFooter className="mt-auto">
                  <Button variant="outline" disabled>
                    Not available yet
                  </Button>
                </CardFooter>
              </Card>
            </div>
          </div>
        </section>

        <section className="border-t">
          <div className="mx-auto flex max-w-5xl flex-col items-center gap-5 px-5 py-20 text-center">
            <h2 className="text-3xl font-semibold tracking-tight">Leave the laptop at home.</h2>
            <p className="text-muted-foreground">AndVibe is coming to Google Play.</p>
            <PlayBadge />
          </div>
        </section>
      </main>
      <SiteFooter />
    </>
  )
}

function PlanList({ items }: { items: string[] }) {
  return (
    <ul className="space-y-2 text-sm">
      {items.map((item) => (
        <li key={item} className="flex items-center gap-2">
          <Check className="size-4 text-chart-1" />
          {item}
        </li>
      ))}
    </ul>
  )
}

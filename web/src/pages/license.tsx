import { Check, X } from 'lucide-react'

import licenseText from '../../../LICENSE?raw'

import { SiteFooter } from '@/components/site-footer'
import { SiteHeader } from '@/components/site-header'
import { Badge } from '@/components/ui/badge'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Separator } from '@/components/ui/separator'

const can = ['Use it, including commercially', 'Modify it', 'Distribute it', 'Use any patents it covers']
const must = ['Include the license', 'Keep copyright and notices', 'Mark files you changed']
const cannot = ['Use the AndVibe trademarks', 'Hold contributors liable', 'Expect a warranty']

export function License() {
  return (
    <>
      <SiteHeader />
      <main className="mx-auto max-w-3xl px-5 py-16">
        <Badge variant="secondary" className="font-mono">
          Apache-2.0
        </Badge>
        <h1 className="mt-4 text-3xl font-semibold tracking-tight">License</h1>
        <p className="mt-3 text-muted-foreground">
          The AndVibe source code is released under the Apache License, Version 2.0. Here is the
          short version. The full text below is what counts.
        </p>

        <div className="mt-8 grid gap-4 sm:grid-cols-3">
          <Summary title="You can" items={can} tone="yes" />
          <Summary title="You must" items={must} tone="yes" />
          <Summary title="You can't" items={cannot} tone="no" />
        </div>

        <Separator className="my-10" />

        <h2 className="text-xl font-semibold">Full text</h2>
        <pre className="mt-4 overflow-x-auto rounded-md border bg-tape p-5 font-mono text-xs leading-relaxed whitespace-pre-wrap text-tape-foreground">
          {licenseText}
        </pre>
      </main>
      <SiteFooter />
    </>
  )
}

function Summary({ title, items, tone }: { title: string; items: string[]; tone: 'yes' | 'no' }) {
  const Icon = tone === 'yes' ? Check : X
  return (
    <Card className="gap-3">
      <CardHeader>
        <CardTitle className="text-base">{title}</CardTitle>
      </CardHeader>
      <CardContent>
        <ul className="space-y-2 text-sm text-muted-foreground">
          {items.map((item) => (
            <li key={item} className="flex gap-2">
              <Icon
                className={`mt-0.5 size-4 shrink-0 ${tone === 'yes' ? 'text-chart-1' : 'text-chart-4'}`}
              />
              {item}
            </li>
          ))}
        </ul>
      </CardContent>
    </Card>
  )
}

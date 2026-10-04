import { Area, AreaChart, Bar, BarChart, CartesianGrid, XAxis, YAxis } from 'recharts'

import { Badge } from '@/components/ui/badge'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import {
  ChartContainer,
  ChartTooltip,
  ChartTooltipContent,
  type ChartConfig,
} from '@/components/ui/chart'

// One illustrative agent run: 20 steps, adding a feature and 3 tests to a repo that had 9.
const toolCalls = [
  { tool: 'read', calls: 6 },
  { tool: 'grep', calls: 3 },
  { tool: 'edit', calls: 5 },
  { tool: 'write', calls: 1 },
  { tool: 'test', calls: 4 },
  { tool: 'diff', calls: 1 },
]

const testsBySteps = [
  { step: 1, passing: 9 },
  { step: 6, passing: 8 },
  { step: 10, passing: 10 },
  { step: 14, passing: 11 },
  { step: 18, passing: 12 },
  { step: 20, passing: 12 },
]

const toolConfig = {
  calls: { label: 'Calls', color: 'var(--chart-2)' },
} satisfies ChartConfig

const testsConfig = {
  passing: { label: 'Tests passing', color: 'var(--chart-1)' },
} satisfies ChartConfig

export function RunCharts() {
  return (
    <div className="grid gap-4 md:grid-cols-2">
      <Card>
        <CardHeader>
          <div className="flex items-center justify-between gap-2">
            <CardTitle>Tool calls</CardTitle>
            <Badge variant="outline">Example run</Badge>
          </div>
          <CardDescription>
            The model asks, the phone runs the tool on your repo, and the result goes back.
          </CardDescription>
        </CardHeader>
        <CardContent>
          <ChartContainer config={toolConfig} className="aspect-auto h-56 w-full">
            <BarChart data={toolCalls} margin={{ left: -24 }}>
              <CartesianGrid vertical={false} />
              <XAxis dataKey="tool" tickLine={false} axisLine={false} tickMargin={8} />
              <YAxis tickLine={false} axisLine={false} allowDecimals={false} />
              <ChartTooltip cursor={false} content={<ChartTooltipContent hideLabel />} />
              <Bar dataKey="calls" fill="var(--color-calls)" radius={4} />
            </BarChart>
          </ChartContainer>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <div className="flex items-center justify-between gap-2">
            <CardTitle>Tests across the run</CardTitle>
            <Badge variant="outline">Example run</Badge>
          </div>
          <CardDescription>
            An edit breaks a test, the agent sees it, and keeps going until all 12 pass.
          </CardDescription>
        </CardHeader>
        <CardContent>
          <ChartContainer config={testsConfig} className="aspect-auto h-56 w-full">
            <AreaChart data={testsBySteps} margin={{ left: -24 }}>
              <defs>
                <linearGradient id="fillPassing" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="5%" stopColor="var(--color-passing)" stopOpacity={0.5} />
                  <stop offset="95%" stopColor="var(--color-passing)" stopOpacity={0.05} />
                </linearGradient>
              </defs>
              <CartesianGrid vertical={false} />
              <XAxis
                dataKey="step"
                type="number"
                domain={[1, 20]}
                ticks={[1, 5, 10, 15, 20]}
                tickLine={false}
                axisLine={false}
                tickMargin={8}
                tickFormatter={(step) => `step ${step}`}
              />
              <YAxis domain={[6, 12]} tickLine={false} axisLine={false} allowDecimals={false} />
              <ChartTooltip
                cursor={false}
                content={
                  <ChartTooltipContent labelFormatter={(_, items) => `Step ${items[0]?.payload?.step}`} />
                }
              />
              <Area
                dataKey="passing"
                type="stepAfter"
                stroke="var(--color-passing)"
                strokeWidth={2}
                fill="url(#fillPassing)"
              />
            </AreaChart>
          </ChartContainer>
        </CardContent>
      </Card>
    </div>
  )
}

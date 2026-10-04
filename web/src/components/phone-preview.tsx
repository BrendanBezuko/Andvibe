import { Check } from 'lucide-react'

const steps = [
  'read src/app.js',
  'grep "theme"',
  'edit src/app.js',
  'write test/theme.test.js',
  'test',
]

const nav = ['Console', 'Files', 'Git', 'Vibe', 'Build']

export function PhonePreview() {
  return (
    <div className="mx-auto w-[300px] rounded-[2.25rem] border bg-tape p-2.5 shadow-2xl shadow-black/50">
      <div className="flex h-[560px] flex-col overflow-hidden rounded-[1.75rem] bg-background">
        <div className="flex justify-center pt-2">
          <div className="h-1.5 w-16 rounded-full bg-border" />
        </div>

        <div className="flex gap-5 border-b px-4 pt-3 text-[13px]">
          <span className="border-b-2 border-link pb-2 text-link">Chat</span>
          <span className="pb-2 text-muted-foreground">History</span>
          <span className="pb-2 text-muted-foreground">Model</span>
        </div>

        <div className="flex flex-1 flex-col gap-2.5 p-3">
          <div className="flex items-center gap-2 rounded-md border bg-card px-3 py-2 text-[13px]">
            <span className="text-muted-foreground">Editing</span>
            <span className="flex-1 truncate font-semibold text-link">todo-app</span>
            <span className="text-xs text-link">Change</span>
          </div>

          <div className="ml-8 rounded-md bg-secondary px-3 py-2 text-[13px]">
            Add a dark mode toggle and a test for it
          </div>

          <div className="space-y-1 rounded-md border bg-tape px-3 py-2 font-mono text-[11px] text-tape-foreground">
            {steps.map((step) => (
              <div key={step} className="flex items-center gap-2">
                <Check className="size-3 text-chart-1" />
                {step}
              </div>
            ))}
            <div className="pl-5 text-chart-1">12 passed</div>
          </div>

          <div className="mr-6 rounded-md border bg-card px-3 py-2 text-[13px] text-card-foreground">
            Added a toggle in the header that remembers your choice, plus a test for it.
          </div>

          <div className="mt-auto flex items-end gap-2">
            <div className="flex-1 rounded-md border bg-card px-3 py-2 text-[13px] text-muted-foreground">
              Message
            </div>
            <div className="rounded-md bg-primary px-3 py-2 text-[13px] text-primary-foreground">
              Send
            </div>
          </div>
        </div>

        <div className="flex justify-around border-t py-2.5 text-[10px] text-muted-foreground">
          {nav.map((item) => (
            <span key={item} className={item === 'Vibe' ? 'text-link' : undefined}>
              {item}
            </span>
          ))}
        </div>
      </div>
    </div>
  )
}

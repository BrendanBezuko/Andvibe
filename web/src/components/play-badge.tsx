import { PLAY_STORE_URL } from '@/lib/site'
import { cn } from '@/lib/utils'

function PlayMark() {
  return (
    <svg viewBox="0 0 24 24" aria-hidden="true" className="size-6 shrink-0">
      <path fill="#3DDC84" d="M3.6 1.8 13.8 12 3.6 22.2c-.4-.2-.6-.7-.6-1.2V3c0-.5.2-1 .6-1.2z" />
      <path fill="#58A6FF" d="m17.2 8.6-3.4 3.4 3.4 3.4 3.8-2.2c.8-.5.8-1.9 0-2.4z" />
      <path fill="#F85149" d="M13.8 12 3.6 22.2c.3.2.8.2 1.2 0l12.4-6.8z" />
      <path fill="#D29922" d="M17.2 8.6 4.8 1.8c-.4-.2-.9-.2-1.2 0L13.8 12z" />
    </svg>
  )
}

export function PlayBadge({ className }: { className?: string }) {
  const body = (
    <>
      <PlayMark />
      <span className="flex flex-col text-left leading-tight">
        <span className="text-[11px] text-muted-foreground">
          {PLAY_STORE_URL ? 'Get it on' : 'Coming soon to'}
        </span>
        <span className="text-[15px] font-semibold">Google Play</span>
      </span>
    </>
  )
  const base = cn(
    'inline-flex h-12 items-center gap-3 rounded-md border bg-tape px-4 transition-colors',
    className,
  )

  if (PLAY_STORE_URL) {
    return (
      <a href={PLAY_STORE_URL} className={cn(base, 'hover:border-muted-foreground')}>
        {body}
      </a>
    )
  }
  return (
    <span aria-disabled="true" title="Coming soon" className={cn(base, 'cursor-default')}>
      {body}
    </span>
  )
}

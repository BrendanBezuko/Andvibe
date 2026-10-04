import { Logo } from '@/components/logo'

export function SiteFooter() {
  return (
    <footer className="border-t">
      <div className="mx-auto flex max-w-5xl flex-col gap-3 px-5 py-8 text-sm text-muted-foreground sm:flex-row sm:items-center sm:justify-between">
        <div className="flex items-center gap-2">
          <Logo className="size-5" />
          <span>© {new Date().getFullYear()} AndVibe</span>
        </div>
        <div className="flex gap-5">
          <a href="/terms/" className="hover:text-foreground">
            Terms
          </a>
          <a href="/license/" className="hover:text-foreground">
            License
          </a>
          <span>Apache 2.0</span>
        </div>
      </div>
    </footer>
  )
}

import { Logo } from '@/components/logo'

const links = [
  { href: '/#features', label: 'Features' },
  { href: '/#run', label: 'How it runs' },
  { href: '/#pricing', label: 'Pricing' },
  { href: '/license/', label: 'License' },
]

export function SiteHeader() {
  return (
    <header className="sticky top-0 z-10 border-b bg-background/80 backdrop-blur">
      <div className="mx-auto flex h-14 max-w-5xl items-center justify-between px-5">
        <a href="/" className="flex items-center gap-2 font-semibold">
          <Logo />
          AndVibe
        </a>
        <nav className="flex items-center gap-5 text-sm text-muted-foreground">
          {links.map((link) => (
            <a
              key={link.href}
              href={link.href}
              className="hidden transition-colors hover:text-foreground sm:inline last:inline"
            >
              {link.label}
            </a>
          ))}
        </nav>
      </div>
    </header>
  )
}

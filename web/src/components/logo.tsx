import { cn } from '@/lib/utils'

export function Logo({ className }: { className?: string }) {
  return <img src="/logo.png" alt="" aria-hidden="true" className={cn('size-7', className)} />
}

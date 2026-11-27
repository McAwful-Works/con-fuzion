import {
  Bell,
  CircleCheck,
  CircleX,
  Clock,
  EyeOff,
  Flag,
  Pencil,
  ShieldCheck,
  Timer,
  Users,
  type LucideIcon,
} from 'lucide-react';
import type { Notification } from '../../api/notifications';

const QUIET = 'bg-surface-sunken text-ink-muted';

/** Each type's icon and tile color. The words say the same, so neither is needed to read an entry. */
const LOOKS: Record<Notification['type'], { icon: LucideIcon; tile: string }> = {
  SPOT_OFFERED: { icon: Timer, tile: 'bg-offer text-offer-ink' },
  SPOT_CONFIRMED: { icon: CircleCheck, tile: 'bg-going text-going-ink' },
  EVENT_CHANGED: { icon: Pencil, tile: 'bg-waiting text-waiting-ink' },
  EVENT_REMINDER: { icon: Clock, tile: 'bg-hosting text-hosting-ink' },
  FRIEND_ACCEPTED: { icon: Users, tile: 'bg-hosting text-hosting-ink' },
  EVENT_CANCELLED: { icon: CircleX, tile: QUIET },
  EVENT_HIDDEN: { icon: EyeOff, tile: QUIET },
  REPORT_REVIEWED: { icon: ShieldCheck, tile: QUIET },
  REPORT_RECEIVED: { icon: Flag, tile: 'bg-waiting text-waiting-ink' },
};

export function lookOf(type: Notification['type']) {
  // A type newer than this build still gets an entry.
  return LOOKS[type] ?? { icon: Bell, tile: QUIET };
}

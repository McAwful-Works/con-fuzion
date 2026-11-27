import { generate } from 'lean-qr';
import { Copy, Share2 } from 'lucide-react';
import { useMemo, useState } from 'react';
import { useCreateInvite, type Invite } from '../../api/social';
import { ErrorState } from '../../components/ErrorState';

/**
 * The invite link as a QR code, generated here from the link itself. Any phone camera opens it, so
 * there is no scanner in the app. It is drawn as one SVG path of the dark squares, always black on
 * white (not a theme color) so that scanners can read it.
 */
function QrCode({ value }: { value: string }) {
  const { size, path } = useMemo(() => {
    const code = generate(value);
    let d = '';
    for (let y = 0; y < code.size; y++) {
      for (let x = 0; x < code.size; x++) {
        if (code.get(x, y)) d += `M${x} ${y}h1v1h-1z`;
      }
    }
    return { size: code.size, path: d };
  }, [value]);
  const quiet = 4; // the quiet zone scanners need around a code

  return (
    <svg
      role="img"
      aria-label="QR code for your invite link"
      viewBox={`${-quiet} ${-quiet} ${size + quiet * 2} ${size + quiet * 2}`}
      className="rounded-control mx-auto aspect-square w-full max-w-56 bg-white text-black"
      shapeRendering="crispEdges"
    >
      <path d={path} fill="currentColor" />
    </svg>
  );
}

const expiryFormat = new Intl.DateTimeFormat(undefined, {
  month: 'short',
  day: 'numeric',
  hour: 'numeric',
  minute: '2-digit',
  timeZoneName: 'short',
});

function InviteLink({ invite }: { invite: Invite }) {
  const [copied, setCopied] = useState<'yes' | 'no' | null>(null);
  const canShare = typeof navigator.share === 'function';

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(invite.url);
      setCopied('yes');
    } catch {
      setCopied('no');
    }
  };
  const share = async () => {
    try {
      await navigator.share({ title: 'Be my friend on Con-Fuzion', url: invite.url });
    } catch {
      // Dismissing the share sheet rejects; there is nothing to report.
    }
  };

  return (
    <div className="space-y-3">
      <QrCode value={invite.url} />
      <p className="text-sm break-all">{invite.url}</p>
      <div className="flex flex-wrap gap-2">
        <button
          type="button"
          className="btn btn-secondary btn-sm flex-1"
          onClick={() => void copy()}
        >
          <Copy aria-hidden className="size-4" /> Copy link
        </button>
        {canShare && (
          <button
            type="button"
            className="btn btn-secondary btn-sm flex-1"
            onClick={() => void share()}
          >
            <Share2 aria-hidden className="size-4" /> Share
          </button>
        )}
      </div>
      <p role="status" className="text-sm">
        {copied === 'yes' && 'Link copied.'}
        {copied === 'no' && 'Couldn’t copy. Select the link above and copy it yourself.'}
      </p>
      <p className="text-ink-muted text-sm">
        Works once. Expires {expiryFormat.format(new Date(invite.expiresAt))}. Anyone with the link
        can use it, so send it only to the person you mean.
      </p>
    </div>
  );
}

/** "Add a friend": makes a link only when asked, and again only when asked for a new one. */
export function InvitePanel() {
  const create = useCreateInvite();
  const invite = create.data;

  return (
    <section aria-labelledby="invite-heading" className="card space-y-3 p-4">
      <h2 id="invite-heading" className="text-lg font-extrabold">
        Add a friend
      </h2>
      <p className="text-ink-muted text-sm">
        Show your code at the con for them to scan with their phone camera, or send the link. It
        works once and expires in 24 hours.
      </p>
      {invite && <InviteLink invite={invite} />}
      <button
        type="button"
        className={`btn w-full ${invite ? 'btn-secondary' : 'btn-primary'}`}
        disabled={create.isPending}
        onClick={() => create.mutate()}
      >
        {invite ? 'Make a new link' : 'Make an invite link'}
      </button>
      {create.isError && <ErrorState error={create.error} />}
    </section>
  );
}

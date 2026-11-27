import { MapPin, Plus } from 'lucide-react';
import { useState } from 'react';
import { EVENT_DESCRIPTION_MAX, EVENT_LOCATION_MAX, EVENT_TAG_MAX } from '../../api/events';
import { EVENT_TITLE_MAX } from '../../api/events';
import { HIDDEN_LOCATION_TEXT } from './EventCard';
import { Field, SwitchRow } from './formFields';
import type { SectionProps } from './validation';

/** D-017's preset tags. "Other" keeps free text, so the tag itself is still any short label. */
export const PRESET_TAGS = [
  'Tabletop',
  'Photoshoot',
  'Food',
  'Music',
  'Panel',
  'Shopping',
  'Hobby',
];

/** One optional tag: a preset chip, or "Other" and the host's own words. */
function TagField({ values, errors, set }: SectionProps) {
  // A tag that isn't exactly a preset is the host's own, and stays as they wrote it.
  const [other, setOther] = useState(() => values.tag !== '' && !PRESET_TAGS.includes(values.tag));
  const choose = (tag: string, own: boolean) => {
    setOther(own);
    set('tag', tag);
  };

  return (
    <div className="space-y-3">
      <div role="group" aria-labelledby="tag-label" className="field">
        <p id="tag-label" className="field-label">
          Tag (optional)
        </p>
        <div className="flex flex-wrap gap-2">
          {PRESET_TAGS.map((tag) => {
            const on = !other && values.tag === tag;
            return (
              <button
                key={tag}
                type="button"
                className="chip"
                aria-pressed={on}
                onClick={() => choose(on ? '' : tag, false)}
              >
                {tag}
              </button>
            );
          })}
          <button
            type="button"
            className="chip"
            aria-pressed={other}
            // A preset isn't carried into the text box.
            onClick={() => choose('', !other)}
          >
            <Plus aria-hidden="true" className="size-4" />
            Other
          </button>
        </div>
      </div>
      {other && (
        <Field label="Your tag" hint="One short label, such as Karaoke." error={errors.tag}>
          {(p) => (
            <input
              {...p}
              type="text"
              value={values.tag}
              maxLength={EVENT_TAG_MAX}
              onChange={(e) => set('tag', e.target.value)}
            />
          )}
        </Field>
      )}
    </div>
  );
}

export function DetailsFields(props: SectionProps) {
  const { values, errors, set } = props;
  return (
    <>
      <Field label="Title" error={errors.title}>
        {(p) => (
          <input
            {...p}
            type="text"
            value={values.title}
            maxLength={EVENT_TITLE_MAX}
            onChange={(e) => set('title', e.target.value)}
          />
        )}
      </Field>
      <TagField {...props} />
      <Field label="Description (optional)" error={errors.description}>
        {(p) => (
          <textarea
            {...p}
            value={values.description}
            rows={4}
            maxLength={EVENT_DESCRIPTION_MAX}
            onChange={(e) => set('description', e.target.value)}
          />
        )}
      </Field>
      <Field label="Location" error={errors.location}>
        {(p) => (
          <div className="relative">
            <MapPin
              aria-hidden="true"
              className="text-ink-muted pointer-events-none absolute top-3.5 left-3 size-5"
            />
            <input
              {...p}
              className={`${p.className} pl-10`}
              type="text"
              value={values.location}
              maxLength={EVENT_LOCATION_MAX}
              onChange={(e) => set('location', e.target.value)}
            />
          </div>
        )}
      </Field>
      <SwitchRow
        label="Hide the location until people are confirmed"
        hint={`Viewers see “${HIDDEN_LOCATION_TEXT}”.`}
        checked={values.locationConfirmedOnly}
        onChange={(on) => set('locationConfirmedOnly', on)}
      />
    </>
  );
}

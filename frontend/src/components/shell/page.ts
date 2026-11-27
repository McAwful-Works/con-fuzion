import type { Params } from 'react-router';

/** A route's `handle`, for a page that changes the phone top bar from the wordmark to Back and a title. */
export interface ShellPage {
  title: string;
  /** Where Back goes when there's no earlier page in this visit. */
  parent: (params: Readonly<Params>) => string;
  /** A detail page: phones hide the tab bar on it. */
  detail?: boolean;
  /** Anyone else gets the plain top bar, so the not-found page they see doesn't name the area. */
  moderatorsOnly?: boolean;
}

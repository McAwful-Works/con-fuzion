/**
 * MIT License
 * <p>
 * Copyright (c) 2024 McAwful-Works
 * <p>
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to the following conditions:
 * <p>
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the
 * Software.
 * <p>
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
 * WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
 * OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package app.confuzion.backend.service;

import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import app.confuzion.backend.repository.RsvpRepository;

/**
 * Ends offers that ran out (D-014): each becomes {@code EXPIRED}, and its spot
 * is offered to the next person in line, or confirms them near the start. Every
 * task runs it; {@link RsvpRepository#lockDueEvents} skips the events another
 * task or a request holds, so they share the work without waiting on each
 * other. Reads already treat an offer past its deadline as expired (rule 15),
 * so the interval only decides how soon the next person hears.
 *
 * @author mcawful
 */
@Component
@EnableScheduling
public class OfferExpiryJob {
	/**
	 * The most events one transaction handles.
	 */
	static final int BATCH = 100;

	/**
	 * The {@link RsvpRepository}.
	 */
	private final RsvpRepository rsvpRepository;

	/**
	 * Runs each batch in its own transaction, which holds the batch's event locks.
	 */
	private final TransactionTemplate transactionTemplate;

	/**
	 * Creates the job.
	 *
	 * @param rsvpRepository      the {@link RsvpRepository}
	 * @param transactionTemplate the {@link TransactionTemplate}
	 */
	public OfferExpiryJob(RsvpRepository rsvpRepository, TransactionTemplate transactionTemplate) {
		this.rsvpRepository = rsvpRepository;
		this.transactionTemplate = transactionTemplate;
	}

	/**
	 * Ends every offer past its deadline, a batch of events at a time, until a
	 * batch comes back short. The interval defaults to 30 seconds, and tests may
	 * lower it with {@code con-fuzion.offers.expiry-interval}.
	 */
	@Scheduled(fixedDelayString = "${con-fuzion.offers.expiry-interval:30s}", initialDelayString = "${con-fuzion.offers.expiry-interval:30s}")
	public void run() {
		while (expireBatch() == BATCH) {
			// A full batch: there may be more.
		}
	}

	/**
	 * Locks a batch of events with offers past their deadline and ends those
	 * offers, re-checking each deadline under the lock.
	 *
	 * @return how many events the batch locked
	 */
	int expireBatch() {
		return transactionTemplate.execute(tx -> {
			var events = rsvpRepository.lockDueEvents(BATCH);
			events.forEach(eventId -> rsvpRepository.expireDue(eventId, null));
			return events.size();
		});
	}
}

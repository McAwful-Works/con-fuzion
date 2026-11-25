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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import app.confuzion.backend.repository.RsvpRepository;

/**
 * Ends offers that ran out (D-014): each becomes {@code EXPIRED}, and its spot
 * is offered to the next person in line, or confirms them near the start. Every
 * task runs it; {@link RsvpRepository#tryLock} skips the events another task or
 * a request holds, so they share the work without waiting on each other. Reads
 * already treat an offer past its deadline as expired (rule 15), so the
 * interval only decides how soon the next person hears.
 *
 * @author mcawful
 */
@Component
public class OfferExpiryJob {
	/**
	 * The logger.
	 */
	private static final Logger LOGGER = LoggerFactory.getLogger(OfferExpiryJob.class);

	/**
	 * The most events one run handles; the next run picks up the rest.
	 */
	static final int BATCH = 1_000;

	/**
	 * The {@link RsvpRepository}.
	 */
	private final RsvpRepository rsvpRepository;

	/**
	 * Runs each event in its own transaction, which holds that event's lock.
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
	 * Ends the offers past their deadline on a batch of events, one event per
	 * transaction, so a failure on one event never holds up the others. Each
	 * deadline is checked again under the event's lock. The interval defaults to 30
	 * seconds; {@code con-fuzion.offers.expiry-interval} changes it, and the tests
	 * raise it so only their own calls run the job.
	 */
	@Scheduled(fixedDelayString = "${con-fuzion.offers.expiry-interval:PT30S}", initialDelayString = "${con-fuzion.offers.expiry-interval:PT30S}")
	public void run() {
		for (var eventId : rsvpRepository.findDueEvents(BATCH)) {
			try {
				transactionTemplate.executeWithoutResult(tx -> {
					if (rsvpRepository.tryLock(eventId))
						rsvpRepository.expireDue(eventId, null);
				});
			} catch (RuntimeException e) {
				LOGGER.warn("Expiring offers on event {} failed", eventId, e);
			}
		}
	}
}

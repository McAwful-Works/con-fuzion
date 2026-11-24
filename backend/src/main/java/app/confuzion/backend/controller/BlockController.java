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
package app.confuzion.backend.controller;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import app.confuzion.backend.model.record.PersonListResponse;
import app.confuzion.backend.model.record.ResponseRecord;
import app.confuzion.backend.service.BlockService;
import app.confuzion.backend.util.HttpUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * The {@link RestController} for the signed-in user's blocks. A block is a full
 * mutual cutoff (D-011). The user ID always comes from the session.
 *
 * @author mcawful
 */
@RestController
@RequestMapping("/api/blocks")
@Tag(name = "blocks", description = "Blocked users")
public class BlockController {
	/**
	 * The {@link BlockService}.
	 */
	private final BlockService blockService;

	/**
	 * Creates the controller.
	 *
	 * @param blockService the {@link BlockService}
	 */
	public BlockController(BlockService blockService) {
		this.blockService = blockService;
	}

	/**
	 * Returns the users the signed-in user has blocked.
	 *
	 * @param authentication the session's {@link Authentication}
	 * @return the {@link PersonListResponse} envelope
	 */
	@GetMapping
	@Operation(summary = "List the users the signed-in user has blocked", description = "Sorted by display name. "
			+ "Never shows who has blocked the user.")
	public ResponseRecord<PersonListResponse> getBlocks(@Parameter(hidden = true) Authentication authentication) {
		return new ResponseRecord<>(blockService.getBlocks(HttpUtil.userId(authentication)));
	}

	/**
	 * Blocks a user. Repeating it changes nothing.
	 *
	 * @param authentication the session's {@link Authentication}
	 * @param userId         the user to block
	 */
	@PutMapping("/{userId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(summary = "Block a user", description = "Idempotent. In one transaction it ends any friendship between "
			+ "the two and removes each from the other's circles. From then on, whichever of them made the block, "
			+ "neither can find or pick the other, use the other's invites, or see the other's events. A user ID that "
			+ "doesn't exist is also a 204, so it reveals nothing; the documented 404 never happens.")
	// Declaring the 400 drops springdoc's inferred 204, so the 204 is declared too.
	@ApiResponse(responseCode = "204", description = "No Content")
	@ApiResponse(responseCode = "400", description = "The request failed validation, or the user ID is the caller's own.")
	public void blockUser(@Parameter(hidden = true) Authentication authentication,
			@Parameter(description = "The user to block") @PathVariable UUID userId) {
		blockService.block(HttpUtil.userId(authentication), userId);
	}

	/**
	 * Removes a block the signed-in user made.
	 *
	 * @param authentication the session's {@link Authentication}
	 * @param userId         the blocked user
	 */
	@DeleteMapping("/{userId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(summary = "Unblock a user", description = "Removes only the caller's own block; a block the other person "
			+ "made stays. Friendships and circle memberships aren't restored. Always 204, also when there was no "
			+ "block or no such user; the documented 404 never happens.")
	public void unblockUser(@Parameter(hidden = true) Authentication authentication,
			@Parameter(description = "The blocked user") @PathVariable UUID userId) {
		blockService.unblock(HttpUtil.userId(authentication), userId);
	}
}

// SPDX-License-Identifier: Apache-2.0
/**
 * Experimental immutable types for MeridianDB's initial temporal contract.
 * Coordinates use signed epoch microseconds; commit positions carry database lineage.
 * These types do not supply an engine, persistence, a timestamp parser or a stable API.
 */
package org.meridiandb.temporal;

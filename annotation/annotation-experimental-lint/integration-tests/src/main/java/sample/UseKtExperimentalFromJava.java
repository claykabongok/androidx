/*
 * Copyright 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package sample;

import kotlin.UseExperimental;

@SuppressWarnings({"unused", "WeakerAccess"})
class UseKtExperimentalFromJava {
    /**
     * Unsafe call into an experimental class.
     */
    int getDateUnsafe() {
        DateProviderKt dateProvider = new DateProviderKt();
        return dateProvider.getDate();
    }

    @ExperimentalDateTimeKt
    int getDateExperimental() {
        DateProviderKt dateProvider = new DateProviderKt();
        return dateProvider.getDate();
    }

    @UseExperimental(markerClass = ExperimentalDateTimeKt.class)
    int getDateUseExperimental() {
        DateProviderKt dateProvider = new DateProviderKt();
        return dateProvider.getDate();
    }

    void displayDate() {
        System.out.println("" + getDateUnsafe());
    }

    // Tests involving multiple experimental markers.

    /**
     * Unsafe call into an experimental class.
     */
    @ExperimentalDateTimeKt
    int getDateExperimentalLocationUnsafe() {
        DateProviderKt dateProvider = new DateProviderKt();
        LocationProviderKt locationProvider = new LocationProviderKt();
        return dateProvider.getDate() + locationProvider.getLocation();
    }

    @ExperimentalDateTimeKt
    @ExperimentalLocationKt
    int getDateAndLocationExperimental() {
        DateProviderKt dateProvider = new DateProviderKt();
        LocationProviderKt locationProvider = new LocationProviderKt();
        return dateProvider.getDate() + locationProvider.getLocation();
    }

    @UseExperimental(markerClass = ExperimentalDateTimeKt.class)
    @ExperimentalLocationKt
    int getDateUseExperimentalLocationExperimental() {
        DateProviderKt dateProvider = new DateProviderKt();
        LocationProviderKt locationProvider = new LocationProviderKt();
        return dateProvider.getDate() + locationProvider.getLocation();
    }

    @UseExperimental(markerClass = { ExperimentalDateTimeKt.class, ExperimentalLocationKt.class })
    int getDateAndLocationUseExperimental() {
        DateProviderKt dateProvider = new DateProviderKt();
        LocationProviderKt locationProvider = new LocationProviderKt();
        return dateProvider.getDate() + locationProvider.getLocation();
    }
}

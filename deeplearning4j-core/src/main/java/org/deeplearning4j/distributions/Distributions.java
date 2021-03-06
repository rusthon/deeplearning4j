/*
 * Copyright 2015 Skymind,Inc.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package org.deeplearning4j.distributions;

import org.apache.commons.math3.distribution.NormalDistribution;
import org.apache.commons.math3.distribution.ExponentialDistribution;
import org.apache.commons.math3.distribution.RealDistribution;
import org.apache.commons.math3.distribution.UniformRealDistribution;
import org.apache.commons.math3.random.RandomGenerator;
import org.deeplearning4j.util.MultiDimensionalMap;

public class Distributions {

    private static MultiDimensionalMap<RandomGenerator,Double,RealDistribution> normalDistributions =
        MultiDimensionalMap.newHashBackedMap();
    private static MultiDimensionalMap<RandomGenerator,Double,RealDistribution> exponentialDist =
        MultiDimensionalMap.newHashBackedMap();
    private static MultiDimensionalMap<RandomGenerator,Double,RealDistribution> uniformDist =
        MultiDimensionalMap.newHashBackedMap();

    /**
     * Returns a exponential distribution
     * with a mean of 0 and a standard deviation of std
     * @param rng the rng to use
     * @param mean the standard mean to use
     * @return a normal distribution with a mean of 0 and a standard deviation of 1
     */
    public static RealDistribution exponential(RandomGenerator rng,double mean) {
        if(exponentialDist.get(rng,mean) == null) {
            RealDistribution ret =  new ExponentialDistribution(rng,1.0,
                ExponentialDistribution.DEFAULT_INVERSE_ABSOLUTE_ACCURACY);
            exponentialDist.put(rng,mean,ret);
        }
        return exponentialDist.get(rng,mean);
    }

    /**
     * Returns a normal distribution
     * with a mean of 0 and a standard deviation of std
     * @param rng the rng to use
     * @param std the standard deviation to use
     * @return a normal distribution with a mean of 0 and a standard deviation of 1
     */
    public static RealDistribution normal(RandomGenerator rng,double std) {
        if(normalDistributions.get(rng,std) == null) {
            RealDistribution ret =  new NormalDistribution(rng,0,std,
                NormalDistribution.DEFAULT_INVERSE_ABSOLUTE_ACCURACY);
            normalDistributions.put(rng,std,ret);
            return ret;
        }
        return normalDistributions.get(rng,std);
    }

    /**
     * Returns a uniform distribution with a
     * min of -fanIn and a max of positivefanIn
     * @param rng the rng to use
     * @param fanIn the fanin to use
     * @return a uniform distribution with a min of -fanIn
     * and a max of fanIn
     */
    public static RealDistribution uniform(RandomGenerator rng,double fanIn) {
        fanIn = Math.abs(fanIn);
        if(uniformDist.get(rng,fanIn) == null) {
            RealDistribution ret = new UniformRealDistribution(rng,-fanIn,fanIn);
            uniformDist.put(rng,fanIn,ret);
            return ret;
        }

        return uniformDist.get(rng,fanIn);
    }

    /**
     * Returns a uniform distribution with a
     * min of min and max of fanOut
     * @param rng the rng to use
     * @param min the fanin to use
     *  @param max the fanout to use
     * @return a uniform distribution with a min of -min
     * and a max of min
     */
    public static RealDistribution uniform(RandomGenerator rng,double min,double max) {
        return new UniformRealDistribution(rng, min,max);
    }

    /**
     * Returns a uniform distribution
     * based on the number of ins and outs
     * @param rng the rng to use
     * @param nIn the number of inputs
     * @param nOut the number of outputs
     * @return
     */
    public static RealDistribution uniform(RandomGenerator rng,int nIn,int nOut) {
        double fanIn = -4 * Math.sqrt(6. / (nOut + nIn));
        return uniform(rng,fanIn);
    }

    /**
     * Returns a uniform distribution with a
     * min of -fanIn and a max of positivefanIn
     * @param rng the rng to use
     * @return a uniform distribution with a min of -fanIn
     * and a max of fanIn
     */
    public static RealDistribution uniform(RandomGenerator rng) {
        double fanIn = 0.1;
        return new UniformRealDistribution(rng,-fanIn,fanIn);
    }

}

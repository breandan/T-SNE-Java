package com.jujutsu.tsne.barneshut;

import static java.lang.Math.exp;
import static java.lang.Math.log;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;
import java.util.concurrent.RecursiveAction;
import java.util.stream.DoubleStream;
import java.util.stream.IntStream;

import com.jujutsu.tsne.TSneConfiguration;
import com.jujutsu.utils.MatrixOps;

public class ParallelBHTsne extends BHTSne {

	private ForkJoinPool gradientPool;

	@Override
	double[][] run(TSneConfiguration config) {
		gradientPool = new ForkJoinPool(Runtime.getRuntime().availableProcessors());
		double [][] Y = super.run(config);
		gradientPool.shutdown();
		return Y;
	}

	class RecursiveGradientUpdater extends RecursiveAction {
		final static long serialVersionUID = 1L;
		int startIdx = -1;
		int endIdx = -1;
		int limit = 100;
		int N;
		int no_dims;
		double[] Y;
		double momentum;
		double eta; 
		double[] dY; 
		double[] uY;
		double[] gains;

		public RecursiveGradientUpdater(int n, int no_dims, double[] Y, double momentum, double eta, double[] dY, double[] uY,
				double[] gains, int startIdx, int endIdx, int limit) {
			super();
			this.startIdx = startIdx;
			this.endIdx = endIdx;
			this.limit = limit;
			N = n;
			this.no_dims = no_dims;
			this.Y = Y;
			this.momentum = momentum;
			this.eta = eta;
			this.dY = dY;
			this.uY = uY;
			this.gains = gains;
		}

		@Override
		protected void compute() {
			if ( (endIdx-startIdx) <= limit ) {
				for (int n = startIdx; n < endIdx; n++) {
					// Update gains
					gains[n] = (sign_tsne(dY[n]) != sign_tsne(uY[n])) ? (gains[n] + .2) : (gains[n] * .8);
					if(gains[n] < .01) gains[n] = .01;

					// Perform gradient update (with momentum and gains)
					Y[n] = Y[n] + uY[n];
					uY[n] = momentum * uY[n] - eta * gains[n] * dY[n];
				}
			}
			else {
				int range = (endIdx-startIdx);
				int startIdx1 = startIdx;
				int endIdx1 = startIdx + (range / 2);
				int endIdx2 = endIdx;
				invokeAll(new RecursiveGradientUpdater(N, no_dims, Y, momentum, eta, dY, uY, gains, startIdx1, endIdx1, limit),
						new RecursiveGradientUpdater(N, no_dims, Y, momentum, eta, dY, uY, gains, endIdx1, endIdx2, limit));
			}
		}
	}

	@Override
	void updateGradient(int N, int no_dims, double[] Y, double momentum, double eta, double[] dY, double[] uY,
			double[] gains) {
		RecursiveGradientUpdater dslr = new RecursiveGradientUpdater(N, no_dims, Y, momentum, eta, dY, uY, gains,0,N * no_dims,N/(Runtime.getRuntime().availableProcessors()*10));                
		gradientPool.invoke(dslr);
	}

	// Compute gradient of the t-SNE cost function (using Barnes-Hut algorithm)
	@Override
	void computeGradient(double [] P, int [] inp_row_P, 
			int [] inp_col_P, double [] inp_val_P, 	double [] Y, int N, int D, 
			double [] dC, double theta)
	{
		// Construct space-partitioning tree on current map
		ParallelSPTree tree = new ParallelSPTree(D, Y, N);

		double totalSum_Q = 0.0;
		double[] sum_Q = new double[N];
		double[] pos_f = new double[N * D];
		double[][] neg_f = new double[N][D];
		double[][] buff = new double[N][D];
		tree.computeEdgeForces(inp_row_P, inp_col_P, inp_val_P, N, pos_f);

		IntStream.range(0, N).parallel().forEach(i -> {
			tree.computeNonEdgeForces(i, theta, neg_f[i], buff[i], sum_Q);
		});
		totalSum_Q = DoubleStream.of(sum_Q).sum();

		// Compute final t-SNE gradient
		for (int n = 0; n < N; n++)
		{
			for (int d = 0; d < D; d++)
			{
				dC[n * D + d] = pos_f[n * D + d] - (neg_f[n][d] / totalSum_Q);
			}
		}
	}

	@Override
	// Compute input similarities with a fixed perplexity using ball trees
	void computeGaussianPerplexity(double [] X, int N, int D, int [] _row_P, int [] _col_P, double [] _val_P, double perplexity, int K) {
		if(perplexity > K) System.out.println("Perplexity should be lower than K!");

		// Allocate the memory we need
		/**_row_P = (int*)    malloc((N + 1) * sizeof(int));
		 *_col_P = (int*)    calloc(N * K, sizeof(int));
		 *_val_P = (double*) calloc(N * K, sizeof(double));
			    if(*_row_P == null || *_col_P == null || *_val_P == null) { Rcpp::stop("Memory allocation failed!\n"); }*/
		int [] row_P = _row_P;
		int [] col_P = _col_P;
		double [] val_P = _val_P;
		double [] cur_P = new double[N - 1];

		row_P[0] = 0;
		for(int n = 0; n < N; n++) row_P[n + 1] = row_P[n] + K;    

		// Build ball tree on data set
		ParallelVpTree<DataPoint> tree = new ParallelVpTree<DataPoint>(gradientPool,distance);
		final DataPoint [] obj_X = new DataPoint [N];
		for(int n = 0; n < N; n++) {
			double [] row = MatrixOps.extractRowFromFlatMatrix(X,n,D);
			obj_X[n] = new DataPoint(D, n, row);
		}
		tree.create(obj_X);

		// VERIFIED THAT TREES LOOK THE SAME
		//System.out.println("Created Tree is: ");
		//			AdditionalInfoProvider pp = new AdditionalInfoProvider() {			
		//				@Override
		//				public String provideInfo(Node node) {
		//					return "" + obj_X[node.index].index();
		//				}
		//			};
		//			TreePrinter printer = new TreePrinter(pp);
		//			printer.printTreeHorizontal(tree.getRoot());

		// Loop over all points to find nearest neighbors
		List<Future<ParallelVpTree<DataPoint>.ParallelTreeNode.TreeSearchResult>> results = tree.searchMultiple(tree, obj_X, K+1);

		for (Future<ParallelVpTree<DataPoint>.ParallelTreeNode.TreeSearchResult> result : results) {
			ParallelVpTree<DataPoint>.ParallelTreeNode.TreeSearchResult res = null;
			List<Double> distances = null;
			List<DataPoint> indices = null;
			int n = -1;
			try {
				res = result.get();
				distances = res.getDistances();
				indices = res.getIndices();
				n = res.getIndex();
			} catch (InterruptedException | ExecutionException e) {
				e.printStackTrace();
			}

			// Initialize some variables for binary search
			boolean found = false;
			double beta = 1.0;
			double min_beta = -Double.MAX_VALUE;
			double max_beta =  Double.MAX_VALUE;
			double tol = 1e-5;

			// Iterate until we found a good perplexity
			int iter = 0; 
			double sum_P = 0.;
			while(!found && iter < 200) {

				// Compute Gaussian kernel row and entropy of current row
				sum_P = Double.MIN_VALUE;
				double H = .0;
				for(int m = 0; m < K; m++) {
					cur_P[m] = exp(-beta * distances.get(m + 1));
					sum_P += cur_P[m];
					H += beta * (distances.get(m + 1) * cur_P[m]);
				}
				H = (H / sum_P) + log(sum_P);

				// Evaluate whether the entropy is within the tolerance level
				double Hdiff = H - log(perplexity);
				if(Hdiff < tol && -Hdiff < tol) {
					found = true;
				}
				else {
					if(Hdiff > 0) {
						min_beta = beta;
						if(max_beta == Double.MAX_VALUE || max_beta == -Double.MAX_VALUE)
							beta *= 2.0;
						else
							beta = (beta + max_beta) / 2.0;
					}
					else {
						max_beta = beta;
						if(min_beta == -Double.MAX_VALUE || min_beta == Double.MAX_VALUE)
							beta /= 2.0;
						else
							beta = (beta + min_beta) / 2.0;
					}
				}

				// Update iteration counter
				iter++;
			}

			// Row-normalize current row of P and store in matrix 
			for(int m = 0; m < K; m++) {
				cur_P[m] /= sum_P;
				col_P[row_P[n] + m] = indices.get(m + 1).index();
				val_P[row_P[n] + m] = cur_P[m];
			}
		}
	}

}

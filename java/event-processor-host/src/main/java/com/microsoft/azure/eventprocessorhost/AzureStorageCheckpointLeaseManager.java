/*
 * LICENSE GOES HERE
 */

package com.microsoft.azure.eventprocessorhost;

import java.io.IOException;
import java.net.URISyntaxException;
import java.security.InvalidKeyException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.*;

import com.google.gson.Gson;
import com.microsoft.azure.storage.AccessCondition;
import com.microsoft.azure.storage.CloudStorageAccount;
import com.microsoft.azure.storage.StorageException;
import com.microsoft.azure.storage.blob.BlobRequestOptions;
import com.microsoft.azure.storage.blob.CloudBlobClient;
import com.microsoft.azure.storage.blob.CloudBlobContainer;
import com.microsoft.azure.storage.blob.CloudBlobDirectory;
import com.microsoft.azure.storage.blob.CloudBlockBlob;
import com.microsoft.azure.storage.blob.LeaseState;


public class AzureStorageCheckpointLeaseManager implements ICheckpointManager, ILeaseManager
{
    private EventProcessorHost host;
    private String storageConnectionString;
    
    private CloudBlobClient storageClient;
    private CloudBlobContainer eventHubContainer;
    private CloudBlobDirectory consumerGroupDirectory;
    private CloudBlockBlob eventHubInfoBlob;
    
    private Gson gson;
    
    private final static int storageMaximumExecutionTimeInMs = 2 * 60 * 1000; // two minutes
    private final static int leaseIntervalInSeconds = 30;
    private final static String eventHubInfoBlobName = "eventhub.info";
    private final BlobRequestOptions renewRequestOptions = new BlobRequestOptions();

    public AzureStorageCheckpointLeaseManager(String storageConnectionString)
    {
        this.storageConnectionString = storageConnectionString;
    }

    // The EventProcessorHost can't pass itself to the AzureStorageCheckpointLeaseManager constructor
    // because it is still being constructed.
    public void initialize(EventProcessorHost host) throws InvalidKeyException, URISyntaxException, StorageException
    {
        this.host = host;
        
        this.storageClient = CloudStorageAccount.parse(this.storageConnectionString).createCloudBlobClient();
        BlobRequestOptions options = new BlobRequestOptions();
        options.setMaximumExecutionTimeInMs(AzureStorageCheckpointLeaseManager.storageMaximumExecutionTimeInMs);
        this.storageClient.setDefaultRequestOptions(options);
        
        this.eventHubContainer = this.storageClient.getContainerReference(this.host.getEventHubPath());
        
        this.consumerGroupDirectory = this.eventHubContainer.getDirectoryReference(this.host.getConsumerGroupName());
        
        this.eventHubInfoBlob = this.eventHubContainer.getBlockBlobReference(AzureStorageCheckpointLeaseManager.eventHubInfoBlobName);
        
        this.gson = new Gson();

        // The only option that .NET sets on renewRequestOptions is ServerTimeout, which doesn't exist in Java equivalent
    }

    public Future<Boolean> checkpointStoreExists()
    {
        return EventProcessorHost.getExecutorService().submit(new CheckpointStoreExistsCallable());
    }

    public Future<Boolean> createCheckpointStoreIfNotExists()
    {
        return EventProcessorHost.getExecutorService().submit(new CreateCheckpointStoreIfNotExistsCallable());
    }

    public Future<String> getCheckpoint(String partitionId)
    {
        return EventProcessorHost.getExecutorService().submit(new GetCheckpointCallable(partitionId));
    }

    public Iterable<Future<String>> getAllCheckpoints()
    {
        ArrayList<Future<String>> checkpoints = new ArrayList<Future<String>>();
        // TODO for each partition call getCheckpoint()
        return checkpoints;
    }

    public Future<Void> updateCheckpoint(String partitionId, String offset)
    {
        return EventProcessorHost.getExecutorService().submit(new UpdateCheckpointCallable(partitionId, offset));
    }

    public Future<Void> deleteCheckpoint(String partitionId)
    {
        return EventProcessorHost.getExecutorService().submit(new DeleteCheckpointCallable(partitionId));
    }


    public Future<Boolean> leaseStoreExists()
    {
        return EventProcessorHost.getExecutorService().submit(() -> this.eventHubContainer.exists());
    }

    public Future<Boolean> createLeaseStoreIfNotExists()
    {
        return EventProcessorHost.getExecutorService().submit(() -> this.eventHubContainer.createIfNotExists());
    }

    public Future<Lease> getLease(String partitionId)
    {
        return EventProcessorHost.getExecutorService().submit(() -> getLeaseSync(partitionId));
    }
    
    private Lease getLeaseSync(String partitionId) throws URISyntaxException, IOException
    {
    	AzureBlobLease retval = null;
    	try
    	{
			CloudBlockBlob leaseBlob = this.consumerGroupDirectory.getBlockBlobReference(partitionId);
			if (leaseBlob.exists())
			{
				retval = downloadLease(leaseBlob);
			}
		}
    	catch (StorageException se)
    	{
    		handleStorageException(retval, se);
    	}
    	return retval;
    }

    public Iterable<Future<Lease>> getAllLeases()
    {
        ArrayList<Future<Lease>> leaseFutures = new ArrayList<Future<Lease>>();
        Iterable<String> partitionIds = this.host.getPartitionManager().getPartitionIds(); 
        for (String id : partitionIds)
        {
            leaseFutures.add(getLease(id));
        }
        return leaseFutures;
    }

    public Future<Void> createLeaseIfNotExists(String partitionId)
    {
        return EventProcessorHost.getExecutorService().submit(() -> createLeaseIfNotExistsSync(partitionId));
    }
    
    private Void createLeaseIfNotExistsSync(String partitionId) throws URISyntaxException, IOException
    {
    	try
    	{
    		CloudBlockBlob leaseBlob = this.consumerGroupDirectory.getBlockBlobReference(partitionId);
    		Lease lease = new Lease(this.host.getEventHubPath(), this.host.getConsumerGroupName(), partitionId);
    		String jsonLease = this.gson.toJson(lease);
    		this.host.logWithHostAndPartition(partitionId,
    				"CreateLeaseIfNotExist - leaseContainerName: " + this.host.getEventHubPath() + " consumerGroupName: " + this.host.getConsumerGroupName());
    		leaseBlob.uploadText(jsonLease, null, AccessCondition.generateIfNoneMatchCondition("*"), null, null);
    	}
    	catch (StorageException se)
    	{
    		// From .NET: 
    		// Eat any storage exception related to conflict.
    		// This means the blob already exists.
    		this.host.logWithHostAndPartition(partitionId,
    				"CreateLeaseIfNotExist StorageException - leaseContainerName: " + this.host.getEventHubPath() + " consumerGroupName: " + this.host.getConsumerGroupName(),
    				se);
    	}
    	
    	return null;
    }

    public Future<Void> deleteLease(Lease lease)
    {
        return EventProcessorHost.getExecutorService().submit(() -> deleteLeaseSync((AzureBlobLease)lease));
    }
    
    private Void deleteLeaseSync(AzureBlobLease lease) throws StorageException
    {
    	lease.getBlob().deleteIfExists();
    	return null;
    }

    public Future<Boolean> acquireLease(Lease lease)
    {
        return EventProcessorHost.getExecutorService().submit(() -> acquireLeaseSync((AzureBlobLease)lease));
    }
    
    private Boolean acquireLeaseSync(AzureBlobLease lease) throws IOException
    {
    	CloudBlockBlob leaseBlob = lease.getBlob();
    	String newLeaseId = UUID.randomUUID().toString();
    	try
    	{
    		String newToken = null;
	    	if (leaseBlob.getProperties().getLeaseState() == LeaseState.LEASED)
	    	{
	    		newToken = leaseBlob.changeLease(newLeaseId, AccessCondition.generateLeaseCondition(lease.getToken()));
	    	}
	    	else
	    	{
	    		newToken = leaseBlob.acquireLease(AzureStorageCheckpointLeaseManager.leaseIntervalInSeconds, newLeaseId);
	    	}
	    	lease.setToken(newToken);
	    	lease.setOwner(this.host.getHostName());
	    	// Increment epoch each time lease is acquired or stolen by a new host
	    	lease.incrementEpoch();
	    	leaseBlob.uploadText(this.gson.toJson(lease), null, AccessCondition.generateLeaseCondition(lease.getToken()), null, null);
    	}
    	catch (StorageException se)
    	{
    		handleStorageException(lease, se);
    	}
    	
    	return true;
    }

    public Future<Boolean> renewLease(Lease lease)
    {
        return EventProcessorHost.getExecutorService().submit(() -> renewLeaseSync((AzureBlobLease)lease));
    }
    
    private Boolean renewLeaseSync(AzureBlobLease lease)
    {
    	CloudBlockBlob leaseBlob = lease.getBlob();
    	
    	try
    	{
    		leaseBlob.renewLease(AccessCondition.generateLeaseCondition(lease.getToken()), this.renewRequestOptions, null);
    	}
    	catch (StorageException se)
    	{
    		handleStorageException(lease, se);
    	}
    	
    	return true;
    }

    public Future<Boolean> releaseLease(Lease lease)
    {
        return EventProcessorHost.getExecutorService().submit(() -> releaseLeaseSync((AzureBlobLease)lease));
    }
    
    private Boolean releaseLeaseSync(AzureBlobLease lease) throws IOException
    {
    	CloudBlockBlob leaseBlob = lease.getBlob();
    	try
    	{
    		String leaseId = lease.getToken();
    		AzureBlobLease releasedCopy = new AzureBlobLease(lease);
    		releasedCopy.setToken("");
    		releasedCopy.setOwner("");
    		leaseBlob.uploadText(this.gson.toJson(releasedCopy), null, AccessCondition.generateLeaseCondition(leaseId), null, null);
    		leaseBlob.releaseLease(AccessCondition.generateLeaseCondition(leaseId));
    	}
    	catch (StorageException se)
    	{
    		handleStorageException(lease, se);
    	}
    	
    	return true;
    }

    public Future<Boolean> updateLease(Lease lease)
    {
        return EventProcessorHost.getExecutorService().submit(() -> updateLeaseSync((AzureBlobLease)lease));
    }
    
    public Boolean updateLeaseSync(AzureBlobLease lease) throws IOException
    {
    	if (lease == null)
    	{
    		return false;
    	}
    	String token = lease.getToken();
    	if ((token == null) || (token.length() == 0))
    	{
    		return false;
    	}
    	
    	// First, renew the lease to make sure the update will go through.
    	renewLeaseSync(lease);
    	
    	CloudBlockBlob leaseBlob = lease.getBlob();
    	try
    	{
    		leaseBlob.uploadText(this.gson.toJson(lease), null, AccessCondition.generateLeaseCondition(token), null, null);
    	}
    	catch (StorageException se)
    	{
    		handleStorageException(lease, se);
    	}
    	
    	return true;
    }

    private AzureBlobLease downloadLease(CloudBlockBlob blob) throws StorageException, IOException
    {
    	String jsonLease = blob.downloadText();
    	AzureBlobLease blobLease = new AzureBlobLease(this.gson.fromJson(jsonLease, Lease.class), blob);
    	return blobLease;
    }
    
    private void handleStorageException(AzureBlobLease lease, StorageException storageException)
    {
    	// TODO
    }

    
    


    private class CheckpointStoreExistsCallable implements Callable<Boolean>
    {
        public Boolean call()
        {
            return false;
        }
    }

    private class CreateCheckpointStoreIfNotExistsCallable implements Callable<Boolean>
    {
        public Boolean call()
        {
            return false;
        }
    }

    private class GetCheckpointCallable implements Callable<String>
    {
        private String partitionId;

        public GetCheckpointCallable(String partitionId)
        {
            this.partitionId = partitionId;
        }

        public String call()
        {
            return "";
        }
    }

    private class UpdateCheckpointCallable implements Callable<Void>
    {
        private String partitionId;
        private String offset;

        public UpdateCheckpointCallable(String partitionId, String offset)
        {
            this.partitionId = partitionId;
            this.offset = offset;
        }

        public Void call()
        {
            return null;
        }
    }

    private class DeleteCheckpointCallable implements Callable<Void>
    {
        private String partitionId;

        public DeleteCheckpointCallable(String partitionId)
        {
            this.partitionId = partitionId;
        }

        public Void call()
        {
            return null;
        }
    }
}

# Color Match API

This project was built as a recruitement project.

This is an API designed to handle products with belonging dominant colors. It is used to retrieve products that have a 
color close to a provided product.

## API

The REST API has multiple endpoints for handling the products.

### Important API endpoints

1. `/importProducts` This is an endpoint for importing the products from a CSV file.
It takes a file path String, in simple plain text format from the Response Body.
The format *must* include the extension of the file. The API accepts paths existing on the Docker container. Example:
`/usr/api-service/res/products_lacoste_sample.csv`. 
The path is case-sensitive and has to be exact, otherwise the Batch Job cannot run properly.
2. `/getColor/{id}` This retrieves the dominant color stored in the database for a given product. A product id 
is needed in the path. Example: `/getColor/PH4012-00-CNQ`
3. `/loadColor/{id}` This endpoint uses the Google Vision API to load the image from the database for 
a given product, and find the first dominant color and save it for the product. A product id is needed in the path.
It also checks whether a product already has a dominant color, and only loads a new one if it doesn't 
(the value is null). Example: `/loadColor/PH4012-00-CNQ` will load the dominant color using the Vision API,
and persist it to the database.
4. `/loadColorForAllProducts` This is the endpoint that loads the dominant colors for all products in the database.
This will run the Google Vision API for the entire database. It checks all product elements and if they
do not have an existing dominant color, it will load all of them.
The API does not re-load dominant colors for records that already have a dominant color.
It also checks whether the image found in the database is from a working path.
5. `/getProductsOfColor/{id}/{n}` This endpoint is responsible for the main functionality of this API.
It searches the database for products that have a dominant color close to the one of the product provided.
This needs a product `ID` and an `n` number for retrieving the `n` closest elements to the given product. Example:
`/getProductsOfColor/L1212-00-132/15` will retrieve the 15 products that are closest in color to the provided 
`L1212-00-132`.

## Sample data

`docker/res/products_lacoste_sample.csv` is the product catalogue that was provided with the assignment in 2021.
It is a public snapshot of the Lacoste catalogue: 499 polo shirts from the French Lacoste website. It contains the real
Lacoste product IDs, product names, gender, material composition and sleeve type, plus links to the public product pages
on `www.lacoste.com` and the product images on `image1.lacoste.com`. It contains no prices, customer data or other
private data. The links date from 2021 and may no longer resolve, which matters for the Google Vision API step, as it
needs a reachable image.

`docker/res/Fake_Product_data_200k.csv` is a generated data set in the same format, used for testing the import with
larger volumes. It was generated with `docker/res/fake_data_generator.py`, which uses
[Faker](https://faker.readthedocs.io/) with a French locale. Data sets of other sizes can be generated with it, e.g.
`python docker/res/fake_data_generator.py 2000000` for 2 million products.

## Tech and details of the application

The application uses Java 11 with Spring Boot, to create a RESTful API to manage product colors.
It uses a PostgreSQL database as PostgreSQL is a great choice for dealing with CSV files and I found that I could map
the required values the best with this database (it also supports arrays).
The application also uses Docker to connect the server to the database in the same container, for easier management
of environment variables and private data.

There are three main parts to this application.

### Part I

You can import products from a CSV file through a REST API endpoint. This uses a Spring Batch job
to read the CSV file using a FlatFileItemReader and then persists them to the database. This uses a chunk-based,
multi-threaded implementation to handle bigger data sets with consistent memory and CPU usage.

The import happens in a way that it does not overwrite records in the database that already have a dominant color.
Because the CSV data does not contain a dominant color, it would normally overwrite the database-stored ones with 
null values.

The database I am using is PostgreSQL, and I use a regular `RepositoryItemWriter` to use Spring JPA to persist 
the entities. This is a thread-safe solution given the multi-threaded implementation.

The `ThreadPoolTaskExecutor` is used to separate the processing task to multiple, reusable threads.
Better configuration of this could be used.

I believe, currently the Big O notation for this is O(n). The performance of the reading and writing job
could be further improved by using remote chunking of a Step, or by partitioning.
Spring Batch provides solutions for this.

Functionality-wise, this part could be improved by handling other sources of data instead of just a CSV file. 
I could define a new ItemReader in the Spring Batch job, and decide based on JobProperties, what type of data 
to process with which reader. The Batch job is configured in `ImportJobBatchConfiguration` 
and the job is initiated from `importProductsFromFilePath(filePath)` within `ProductService`.

I have tested the import on large, generated data sets. On average, I achieved 1m 4s with a data set of 200000 items.
This is drastically better than a simple single-threaded implementation. I also tried taking care of thread safety,
but a multi-threaded implementation implies that the Batch Job cannot be restarted. If a job fails,
it will not be able to pick up from where it left off. As a solution, Spring Batch could be replaced with better
alternatives, such as Apache Spark.

### Part II

I have implemented the usage of the Google Vision API with the help of Spring GCP. Using the `/loadColorForAllProducts`
endpoint, it starts a Batch job to read the data from the database, then run the Vision API algorithm on each image path,
then finally persist all the updated data to the database.

The Batch job configuration is found in `DomColorJobBatchConfiguration`. It is very similar to the job configured for
the import of products. In this case, I use a `RepositoryItemReader` to read from the database. This is a paging
ItemReader which is a better option to use with a multi-threaded implementation, as unlike cursor-based ItemReaders,
this is thread-safe. There is a `pageSize` set for it, and it reads that many records from the database. If a chunk is 
not processed successfully, data can be lost. A reasonably high page size will result in better performance.

This implementation uses the same `ThreadPoolTaskExecutor` as the import job does. It also has an ItemProcessor
called `DomColorProductItemProcessor` to perform the call to the Google Vision API implementation in `VisionService`.
It also checks for null values in dominant colors and filters out the non-null values.

This could be improved by adding a switch to be able to overwrite existing dominant colors in the database.

`VisionService` contains the details to retrieving the Dominant Color for a given product's image.
The implementation includes loading the image path, currently using a `HttpsURLConnection`, but this could be
improved by adding multiple ways of loading the picture (e.g. a local path file loader).

Once the image has been loaded as a Spring Resource, this will be used for the Google Vision API
to retrieve an RGB vector in the form of an int array.

I cannot say for certain the Big O notation for this part, as the Google Vision API's performance is majorly impacted
depending on the type and size of images used. I believe the reading, processing and writing part would be O(n2)
simply because in the ItemProcessor, for every read item I check the database-stored version to see if it has a
dominant color already. I did not go quite enough into this, I think this could be removed and managed differently.

For this, I have added an index to the `Product` entity's ID, to speed things up slightly.

### Part III

The final API. The endpoint `/getProductsOfColor/{id}/{n}` retrieves an n-element list of Products,
that have the closest color proximity to the product provided in `id`.

For this implementation, I have used the findAll method in the JPARepository but this may be more memory-consuming
than necessary. `ProductService` then sorts this list of products, comparing the absolute value of two product's
dominant colors. I used absolute value for measuring the lowest distance. The sorted list is then limited to the value
of `n` and returned to the endpoint.

For the calculation of the color proximity, the RGB vectors (as received by the Vision API) 
have to be converted to CIE L * a * b vectors. As I found no better way of directly converting an RGB value
to a L * a * b one, I went by the basis of the color being of the sRGB profile and I used a natural luminosity,
to achieve the best, natural looking grays. 
The implementation for this is in `ColorProximity`.

In the current state, I think this has an O(n) Big O annotation, but it could be improved by also implementing 
multi-threaded, more scalable options here. Because of the pay-per-use nature of Google Vision API,
I was not able to test this on very large sets of data, apart from the provided product catalog.

Another idea for improving this algorithm is indexing the database by the dominant color stored. I tried indexing
the ID values, however.

### Other comments

Upon using the Google Vision API on all records in the database, I have come up with 133 skipped items
because of the lack of a working image path, and it took overall 5m 13s 128ms. This needs improving, but I think
at this point, the performance might be halted by the Vision API itself, as without the Google Vision algorithm,
I was able to achieve 500ms on average with processing the records here.

There are many smaller things that could use improving, or just a different approach. With little experience in such
projects, I cannot guarantee it will be a flawless processing of items. Multiple API endpoints could be added,
as well as the model improved, by e.g. adding a customized type of ID generation to it. The `Product` model currently 
has no auto-generated ID as adding new products from scratch was not a required feature.

The REST API responses could use improvements too. As I found no way to request JSON data for the main API endpoint,
I have decided to just go with a URI-based one, this is why there's only path variables used. Custom errors could
also be added, to avoid confusing the user.

For the database, I useed a named, virtual volume storing the data, this volume is called `db-data`.

### Testing

I have manually tested the application for various cases.

I tried scenarios where I would give wrong input data:

1. `/getProductsOfColor/L13awr2-00-132/3` results in HTTP Status code 500, and an internal error 
of ProductNotFoundException.
2. `/getProductsOfColor/L1312-00-132` results in HTTP Status code 404 as no such path exists.
3. `/getColor/5` results in HTTP Status code 500, and an internal error of ProductNotFoundException.
4. Wrong URL in image path: does not process it, the exception is thrown to the Spring Batch configuration,
in which the ItemProcessor filters out the record from being processed.

As I was originally using field injection throughout the application, this did not allow mocking the beans. 
I have refactored the dependency injection in the services and controller, but I did not do the tests. 
I attempted mock testing, but it did not work quite as I'd expected it. I've tried unit and integral testing, 
but I ended up not including them.

There might be some issues with the restarting of the Spring Batch job.

I have also discovered that upon changing the data source for the PostgreSQL database in *docker-compose*,
if it is set to a directory on the host machine, there are issues with the database initialization,
which causes the application to quit, as it cannot connect. I did not go into further detail on this issue for now.

## Instructions on running the application

The application runs in Docker, using *docker-compose*. 

The Docker configuration requires some environment variables set before you can run it.

Create an `.env` file in the root directory of the application, based on the `.env.example` file.

Certain resources required by the application are stored in the `docker/res` directory on the host,
which is mounted to/copied to the container to a certain path which can be changed in the environment variables.
This is the directory where you put the CSV file to be imported, for example.
The environment variable for this path is `DOCKER_FILE_RES_DIR`.

For now, the host directory is a fixed location, it can be changed to an environment variable if needed.

**Make sure that `docker/res` contains the csv file to be imported.**
It already contains the Lacoste sample catalogue and the generated data set, see [Sample data](#sample-data).

### Docker environment variables

1. `DB_HOST` is the host for the database. In this Docker configuration, the host name is the same as the database
container's name: `db`.
2. `DB_PORT` is the port at which the application is running. The PostgreSQL database's port is `5432` here.
3. `DB_NAME` the name of the database used. In this case `productdb` is my choice.
4. `DB_USER` the username for the database. 
5. `DB_PASSWORD` the password for the database user.
6. `DOCKER_FILE_RES_DIR` the path for the resource directory used in the Docker container. 
Should not be changed by default.
7. `GCP_CREDENTIALS_FILE` the host path to the JSON key of the Google service account. Optional, defaults to
`./secrets/gcp-credentials.json`. The `secrets/` directory is git-ignored. The key is mounted into the container as a
Docker secret at `/run/secrets/gcp_credentials` (not baked into the image), and `GOOGLE_APPLICATION_CREDENTIALS` is
set to that path, which Spring GCP picks up automatically.

**Normally, the PostgreSQL Docker image should automatically be configured with any username/password combination. 
It will be used as the default.*


After the configuration of the environment variables, you should be able to start the application.

Run the application by using the `docker-compose up --build` command. 
This will initialize the database and the server and start up the application.

After this, you can use the REST API to import a products list and perform actions on it. I have used Postman
to test the endpoints.

### Backup data, restoration

I have created a backup SQL file of all the stored data and inserts in the database (including the Spring Batch tables)
from PostgreSQL, this SQL file can be found in the root folder, under the name `backup_inserts.sql`.

This SQL file stores all the data imported from the CSV file, including the dominant colors.

To restore it, use the following command:
`cat backup_inserts.sql | docker exec -it {postgres_container_id} psql -U {postgres_user}` where the 
*postgres_container_id* can be retrieved from Docker, and the *postgres_user* is the username you have set in the 
`.env` file. 

Retrieve the *postgres_container_id* by using the command `docker ps -a` to find all running containers, and finding
the PostgreSQL container's ID from the result list when the container is running.
